/*
 * Copyright 2010-2023 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.renderer

import org.jetbrains.kotlin.fir.FirElementWithResolveState
import org.jetbrains.kotlin.fir.declarations.ResolveStateAccess

class FirResolvePhaseRenderer {
    internal lateinit var components: FirRendererComponents
    private val printer get() = components.printer

    fun render(element: FirElementWithResolveState) {
        @OptIn(ResolveStateAccess::class)
        printer.print("[${element.resolveState}] ")
    }
}