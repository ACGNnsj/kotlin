/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.type

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.types.ConeDynamicType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.coneTypeSafe

object FirDynamicUnsupportedChecker : FirTypeRefChecker() {
    override fun CheckerContext.check(typeRef: FirTypeRef, reporter: DiagnosticReporter) {
        // It's assumed this checker is only called
        // by within the platform that disallows dynamics
        if (typeRef.coneTypeSafe<ConeDynamicType>() != null) {
            reporter.reportOn(typeRef.source, FirErrors.UNSUPPORTED, "dynamic type")
        }
    }
}
