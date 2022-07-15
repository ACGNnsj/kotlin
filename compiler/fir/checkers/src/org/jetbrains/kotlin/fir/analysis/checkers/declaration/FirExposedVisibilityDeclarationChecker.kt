/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.KtFakeSourceElementKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.EffectiveVisibility
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOnWithSuppression
import org.jetbrains.kotlin.fir.analysis.diagnostics.withSuppressedDiagnostics
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.utils.*
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.toSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirTypeAliasSymbol
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.utils.addToStdlib.safeAs

// TODO: check why coneTypeSafe is necessary at some points inside
object FirExposedVisibilityDeclarationChecker : FirBasicDeclarationChecker() {
    override fun CheckerContext.check(declaration: FirDeclaration, reporter: DiagnosticReporter) {
        when (declaration) {
            is FirAnonymousFunction -> return
            is FirTypeAlias -> checkTypeAlias(declaration, reporter)
            is FirProperty -> checkProperty(declaration, reporter)
            is FirFunction -> checkFunction(declaration, reporter)
            is FirRegularClass -> checkClass(declaration, reporter)
            else -> {}
        }
    }

    private fun CheckerContext.checkClass(declaration: FirRegularClass, reporter: DiagnosticReporter) {
        checkSupertypes(declaration, reporter)
        checkParameterBounds(declaration, reporter)
    }

    private fun CheckerContext.checkSupertypes(declaration: FirRegularClass, reporter: DiagnosticReporter) {
        val classVisibility = declaration.effectiveVisibility

        if (classVisibility == EffectiveVisibility.Local) return
        val supertypes = declaration.superTypeRefs
        val isInterface = declaration.classKind == ClassKind.INTERFACE
        for (supertypeRef in supertypes) {
            val supertype = supertypeRef.coneTypeSafe<ConeClassLikeType>() ?: continue
            val classSymbol = supertype.toRegularClassSymbol(session) ?: continue
            val superIsInterface = classSymbol.classKind == ClassKind.INTERFACE
            if (superIsInterface != isInterface) {
                continue
            }
            val (restricting, restrictingVisibility) = supertype.findVisibilityExposure(this, classVisibility) ?: continue
            withSuppressedDiagnostics(supertypeRef) {
                reporter.reportOn(
                    supertypeRef.source ?: declaration.source,
                    if (isInterface) FirErrors.EXPOSED_SUPER_INTERFACE else FirErrors.EXPOSED_SUPER_CLASS,
                    classVisibility,
                    restricting,
                    restrictingVisibility
                )
            }
        }
    }

    private fun CheckerContext.checkParameterBounds(declaration: FirRegularClass, reporter: DiagnosticReporter) {
        val classVisibility = declaration.effectiveVisibility

        if (classVisibility == EffectiveVisibility.Local) return
        for (parameter in declaration.typeParameters) {
            for (bound in parameter.symbol.resolvedBounds) {
                val (restricting, restrictingVisibility) = bound.coneType.findVisibilityExposure(this, classVisibility) ?: continue
                reporter.reportOn(
                    bound.source,
                    FirErrors.EXPOSED_TYPE_PARAMETER_BOUND,
                    classVisibility,
                    restricting,
                    restrictingVisibility
                )
            }
        }
    }

    private fun CheckerContext.checkTypeAlias(declaration: FirTypeAlias, reporter: DiagnosticReporter) {
        val expandedType = declaration.expandedConeType
        val typeAliasVisibility = declaration.effectiveVisibility

        if (typeAliasVisibility == EffectiveVisibility.Local) return
        val (restricting, restrictingVisibility) = expandedType?.findVisibilityExposure(this, typeAliasVisibility) ?: return
        reporter.reportOn(
            declaration.source,
            FirErrors.EXPOSED_TYPEALIAS_EXPANDED_TYPE,
            typeAliasVisibility,
            restricting,
            restrictingVisibility
        )
    }

    private fun CheckerContext.checkFunction(declaration: FirFunction, reporter: DiagnosticReporter) {
        if (declaration.source?.kind is KtFakeSourceElementKind) {
            return
        }

        var functionVisibility = (declaration as FirMemberDeclaration).effectiveVisibility
        if (declaration is FirConstructor && declaration.isFromSealedClass) {
            functionVisibility = EffectiveVisibility.PrivateInClass
        }

        if (functionVisibility == EffectiveVisibility.Local) return
        if (declaration !is FirConstructor && declaration !is FirPropertyAccessor) {
            declaration.returnTypeRef.coneType
                .findVisibilityExposure(this, functionVisibility)?.let { (restricting, restrictingVisibility) ->
                    reporter.reportOn(
                        declaration.source,
                        FirErrors.EXPOSED_FUNCTION_RETURN_TYPE,
                        functionVisibility,
                        restricting,
                        restrictingVisibility
                    )
                }
        }
        if (declaration !is FirPropertyAccessor) {
            declaration.valueParameters.forEachIndexed { i, valueParameter ->
                if (i < declaration.valueParameters.size) {
                    val (restricting, restrictingVisibility) = valueParameter.returnTypeRef.coneType
                        .findVisibilityExposure(this, functionVisibility) ?: return@forEachIndexed
                    reporter.reportOnWithSuppression(
                        valueParameter,
                        FirErrors.EXPOSED_PARAMETER_TYPE,
                        functionVisibility,
                        restricting,
                        restrictingVisibility,
                        this
                    )
                }
            }
        }
        checkMemberReceiver(declaration.receiverTypeRef, declaration as? FirCallableDeclaration, reporter)
    }

    private fun CheckerContext.checkProperty(declaration: FirProperty, reporter: DiagnosticReporter) {
        if (declaration.isLocal) return
        val propertyVisibility = declaration.effectiveVisibility

        if (propertyVisibility == EffectiveVisibility.Local) return
        declaration.returnTypeRef.coneType
            .findVisibilityExposure(this, propertyVisibility)?.let { (restricting, restrictingVisibility) ->
                if (declaration.fromPrimaryConstructor == true) {
                    reporter.reportOn(
                        declaration.source,
                        FirErrors.EXPOSED_PROPERTY_TYPE_IN_CONSTRUCTOR,
                        propertyVisibility,
                        restricting,
                        restrictingVisibility
                    )
                } else {
                    reporter.reportOn(
                        declaration.source,
                        FirErrors.EXPOSED_PROPERTY_TYPE,
                        propertyVisibility,
                        restricting,
                        restrictingVisibility
                    )
                }
            }
        checkMemberReceiver(declaration.receiverTypeRef, declaration, reporter)
    }

    private fun CheckerContext.checkMemberReceiver(
        typeRef: FirTypeRef?,
        memberDeclaration: FirCallableDeclaration?,
        reporter: DiagnosticReporter
    ) {
        if (typeRef == null || memberDeclaration == null) return
        val receiverParameterType = typeRef.coneType
        val memberVisibility = memberDeclaration.effectiveVisibility

        if (memberVisibility == EffectiveVisibility.Local) return
        val (restricting, restrictingVisibility) = receiverParameterType.findVisibilityExposure(this, memberVisibility) ?: return
        reporter.reportOnWithSuppression(
            typeRef,
            FirErrors.EXPOSED_RECEIVER_TYPE,
            memberVisibility,
            restricting,
            restrictingVisibility,
            this
        )
    }

    private fun ConeKotlinType.findVisibilityExposure(
        context: CheckerContext,
        base: EffectiveVisibility
    ): Pair<FirBasedSymbol<*>, EffectiveVisibility>? {
        val type = this as? ConeClassLikeType ?: return null
        val classSymbol = type.fullyExpandedType(context.session).lookupTag.toSymbol(context.session) ?: return null

        val effectiveVisibility = when (classSymbol) {
            is FirRegularClassSymbol -> classSymbol.effectiveVisibility
            is FirTypeAliasSymbol -> classSymbol.effectiveVisibility
            else -> null
        }
        if (effectiveVisibility != null) {
            when (effectiveVisibility.relation(base, context.session.typeContext)) {
                EffectiveVisibility.Permissiveness.LESS,
                EffectiveVisibility.Permissiveness.UNKNOWN -> {
                    return classSymbol to effectiveVisibility
                }
                else -> {
                }
            }
        }

        for (it in type.typeArguments) {
            it.safeAs<ConeClassLikeType>()?.findVisibilityExposure(context, base)?.let {
                return it
            }
        }

        return null
    }
}
