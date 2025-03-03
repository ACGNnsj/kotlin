/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.checkers

import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrorsDefaultMessages
import org.jetbrains.kotlin.fir.analysis.diagnostics.checkMissingMessages
import org.jetbrains.kotlin.fir.analysis.diagnostics.js.FirJsErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.js.FirJsErrorsDefaultMessages
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.jvm.FirJvmErrorsDefaultMessages
import org.jetbrains.kotlin.fir.analysis.diagnostics.native.FirNativeErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.native.FirNativeErrorsDefaultMessages
import org.jetbrains.kotlin.fir.builder.FirSyntaxErrors
import org.jetbrains.kotlin.fir.builder.FirSyntaxErrorsDefaultMessages
import org.junit.Test

class DefaultMessagesTest {
    @Test
    fun ensureAllMessagesPresent() {
        FirErrorsDefaultMessages.MAP.checkMissingMessages(FirErrors)
        FirJvmErrorsDefaultMessages.MAP.checkMissingMessages(FirJvmErrors)
        FirJsErrorsDefaultMessages.MAP.checkMissingMessages(FirJsErrors)
        FirNativeErrorsDefaultMessages.MAP.checkMissingMessages(FirNativeErrors)
        FirSyntaxErrorsDefaultMessages.MAP.checkMissingMessages(FirSyntaxErrors)
    }
}
