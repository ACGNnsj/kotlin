/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.expression

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.config.ApiVersion
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory0
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.ConstantArgumentKind
import org.jetbrains.kotlin.fir.analysis.checkers.checkConstantArguments
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.declarations.findArgumentByName
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.references.FirErrorNamedReference
import org.jetbrains.kotlin.fir.resolve.fqName
import org.jetbrains.kotlin.fir.types.ConeClassLikeType
import org.jetbrains.kotlin.fir.types.FirErrorTypeRef
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.RequireKotlinConstants

object FirAnnotationExpressionChecker : FirAnnotationCallChecker() {
    private val versionArgumentName = Name.identifier("version")
    private val deprecatedSinceKotlinFqName = FqName("kotlin.DeprecatedSinceKotlin")
    private val sinceKotlinFqName = FqName("kotlin.SinceKotlin")

    private val annotationFqNamesWithVersion = setOf(
        RequireKotlinConstants.FQ_NAME,
        sinceKotlinFqName,
    )

    override fun CheckerContext.check(expression: FirAnnotationCall, reporter: DiagnosticReporter) {
        val argumentMapping = expression.argumentMapping.mapping
        val fqName = expression.fqName(session)
        for (arg in argumentMapping.values) {
            val argExpression = (arg as? FirNamedArgumentExpression)?.expression ?: arg
            checkAnnotationArgumentWithSubElements(argExpression, session, reporter)
                ?.let { reporter.reportOn(argExpression.source, it) }
        }

        this.checkAnnotationsWithVersion(fqName, expression, reporter)
        this.checkDeprecatedSinceKotlin(expression.source, fqName, argumentMapping, reporter)
        this.checkAnnotationUsedAsAnnotationArgument(expression, reporter)
        this.checkNotAClass(expression, reporter)
    }

    private fun CheckerContext.checkAnnotationArgumentWithSubElements(
        expression: FirExpression,
        session: FirSession,
        reporter: DiagnosticReporter
    ): KtDiagnosticFactory0? {

        fun checkArgumentList(args: FirArgumentList): KtDiagnosticFactory0? {
            var usedNonConst = false

            for (arg in args.arguments) {
                val sourceForReport = arg.source

                when (val err = checkAnnotationArgumentWithSubElements(arg, session, reporter)) {
                    null -> {
                        //DO NOTHING
                    }
                    else -> {
                        if (err != FirErrors.ANNOTATION_ARGUMENT_MUST_BE_KCLASS_LITERAL) usedNonConst = true
                        reporter.reportOn(sourceForReport, err)
                    }
                }
            }

            return if (usedNonConst) FirErrors.NON_CONST_VAL_USED_IN_CONSTANT_EXPRESSION
            else null
        }

        when (expression) {
            is FirArrayOfCall -> return checkArgumentList(expression.argumentList)
            is FirVarargArgumentsExpression -> {
                for (arg in expression.arguments) {
                    val unwrappedArg = arg.unwrapArgument()
                    checkAnnotationArgumentWithSubElements(
                        unwrappedArg,
                        session,
                        reporter
                    )?.let { reporter.reportOn(unwrappedArg.source, it) }
                }
            }
            else -> {
                return when (checkConstantArguments(expression, session)) {
                    ConstantArgumentKind.NOT_CONST -> FirErrors.ANNOTATION_ARGUMENT_MUST_BE_CONST
                    ConstantArgumentKind.ENUM_NOT_CONST -> FirErrors.ANNOTATION_ARGUMENT_MUST_BE_ENUM_CONST
                    ConstantArgumentKind.NOT_KCLASS_LITERAL -> FirErrors.ANNOTATION_ARGUMENT_MUST_BE_KCLASS_LITERAL
                    ConstantArgumentKind.KCLASS_LITERAL_OF_TYPE_PARAMETER_ERROR -> FirErrors.ANNOTATION_ARGUMENT_KCLASS_LITERAL_OF_TYPE_PARAMETER_ERROR
                    ConstantArgumentKind.NOT_CONST_VAL_IN_CONST_EXPRESSION -> FirErrors.NON_CONST_VAL_USED_IN_CONSTANT_EXPRESSION
                    null ->
                        //try to go deeper if we are not sure about this function call
                        //to report non-constant val in not fully resolved calls
                        if (expression is FirFunctionCall) checkArgumentList(expression.argumentList)
                        else null
                }
            }
        }
        return null
    }

    private fun CheckerContext.parseVersionExpressionOrReport(
        expression: FirExpression,
        reporter: DiagnosticReporter
    ): ApiVersion? {
        val constantExpression = (expression as? FirConstExpression<*>)
            ?: ((expression as? FirNamedArgumentExpression)?.expression as? FirConstExpression<*>) ?: return null
        val stringValue = constantExpression.value as? String ?: return null
        if (!stringValue.matches(RequireKotlinConstants.VERSION_REGEX)) {
            reporter.reportOn(expression.source, FirErrors.ILLEGAL_KOTLIN_VERSION_STRING_VALUE)
            return null
        }
        val version = ApiVersion.parse(stringValue)
        if (version == null) {
            reporter.reportOn(expression.source, FirErrors.ILLEGAL_KOTLIN_VERSION_STRING_VALUE)
        }
        return version
    }

    private fun CheckerContext.checkAnnotationsWithVersion(
        fqName: FqName?,
        annotation: FirAnnotation,
        reporter: DiagnosticReporter
    ) {
        if (!annotationFqNamesWithVersion.contains(fqName)) return
        val versionExpression = annotation.findArgumentByName(versionArgumentName) ?: return
        val version = parseVersionExpressionOrReport(versionExpression, reporter) ?: return
        if (fqName == sinceKotlinFqName) {
            val specified = session.languageVersionSettings.apiVersion
            if (version > specified) {
                reporter.reportOn(versionExpression.source, FirErrors.NEWER_VERSION_IN_SINCE_KOTLIN, specified.versionString)
            }
        }
    }

    private fun CheckerContext.checkDeprecatedSinceKotlin(
        source: KtSourceElement?,
        fqName: FqName?,
        argumentMapping: Map<Name, FirExpression>,
        reporter: DiagnosticReporter
    ) {
        if (fqName != deprecatedSinceKotlinFqName)
            return

        if (argumentMapping.isEmpty()) {
            reporter.reportOn(source, FirErrors.DEPRECATED_SINCE_KOTLIN_WITHOUT_ARGUMENTS)
        }

        var warningSince: ApiVersion? = null
        var errorSince: ApiVersion? = null
        var hiddenSince: ApiVersion? = null
        for ((name, argument) in argumentMapping) {
            val identifier = name.identifier
            if (identifier == "warningSince" || identifier == "errorSince" || identifier == "hiddenSince") {
                val version = this.parseVersionExpressionOrReport(argument, reporter)
                if (version != null) {
                    when (identifier) {
                        "warningSince" -> warningSince = version
                        "errorSince" -> errorSince = version
                        "hiddenSince" -> hiddenSince = version
                    }
                }
            }
        }

        var isReportDeprecatedSinceKotlinWithUnorderedVersions = false
        if (warningSince != null) {
            if (errorSince != null) {
                isReportDeprecatedSinceKotlinWithUnorderedVersions = warningSince > errorSince
            }

            if (hiddenSince != null && !isReportDeprecatedSinceKotlinWithUnorderedVersions) {
                isReportDeprecatedSinceKotlinWithUnorderedVersions = warningSince > hiddenSince
            }
        }

        if (errorSince != null && hiddenSince != null && !isReportDeprecatedSinceKotlinWithUnorderedVersions) {
            isReportDeprecatedSinceKotlinWithUnorderedVersions = errorSince > hiddenSince
        }

        if (isReportDeprecatedSinceKotlinWithUnorderedVersions) {
            reporter.reportOn(source, FirErrors.DEPRECATED_SINCE_KOTLIN_WITH_UNORDERED_VERSIONS)
        }
    }

    private fun CheckerContext.checkAnnotationUsedAsAnnotationArgument(
        expression: FirAnnotationCall,
        reporter: DiagnosticReporter
    ) {
        val args = expression.argumentList.arguments
        for (arg in args) {
            for (ann in arg.unwrapArgument().annotations) {
                reporter.reportOn(ann.source, FirErrors.ANNOTATION_USED_AS_ANNOTATION_ARGUMENT)
            }
        }
    }

    private fun CheckerContext.checkNotAClass(
        expression: FirAnnotationCall,
        reporter: DiagnosticReporter
    ) {
        val annotationTypeRef = expression.annotationTypeRef
        if (expression.calleeReference is FirErrorNamedReference &&
            annotationTypeRef !is FirErrorTypeRef &&
            annotationTypeRef.coneType !is ConeClassLikeType
        ) {
            reporter.reportOn(annotationTypeRef.source, FirErrors.NOT_A_CLASS)
        }
    }
}
