/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.diagnostics

import org.jetbrains.kotlin.diagnostics.*
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.analysis.collectors.AbstractDiagnosticCollector
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals

fun DiagnosticReporter.reportOnWithSuppression(
    element: FirAnnotationContainer,
    factory: KtDiagnosticFactory0,
    context: MutableDiagnosticContext,
    positioningStrategy: SourceElementPositioningStrategy? = null
) {
    context.withSuppressedDiagnostics(element) {
        reportOn(element.source, factory, positioningStrategy)
    }
}

@OptIn(SymbolInternals::class)
fun DiagnosticReporter.reportOnWithSuppression(
    symbol: FirBasedSymbol<*>,
    factory: KtDiagnosticFactory0,
    context: MutableDiagnosticContext,
    positioningStrategy: SourceElementPositioningStrategy? = null
) {
    context.withSuppressedDiagnostics(symbol.fir) {
        reportOn(symbol.source, factory, positioningStrategy)
    }
}

fun DiagnosticReporter.reportOnWithSuppression(
    element: FirAnnotationContainer,
    factory: KtDiagnosticFactoryForDeprecation0,
    context: MutableDiagnosticContext,
    positioningStrategy: SourceElementPositioningStrategy? = null
) {
    context.withSuppressedDiagnostics(element) {
        reportOn(element.source, factory, positioningStrategy)
    }
}

fun <A : Any> DiagnosticReporter.reportOnWithSuppression(
    element: FirAnnotationContainer,
    factory: KtDiagnosticFactory1<A>,
    a: A,
    context: MutableDiagnosticContext,
    positioningStrategy: SourceElementPositioningStrategy? = null
) {
    context.withSuppressedDiagnostics(element) {
        reportOn(element.source, factory, a, positioningStrategy)
    }
}

fun <A : Any, B : Any> DiagnosticReporter.reportOnWithSuppression(
    element: FirAnnotationContainer,
    factory: KtDiagnosticFactory2<A, B>,
    a: A,
    b: B,
    context: MutableDiagnosticContext,
    positioningStrategy: SourceElementPositioningStrategy? = null
) {
    context.withSuppressedDiagnostics(element) {
        reportOn(element.source, factory, a, b, positioningStrategy)
    }
}

fun <A : Any, B : Any, C : Any> DiagnosticReporter.reportOnWithSuppression(
    element: FirAnnotationContainer,
    factory: KtDiagnosticFactory3<A, B, C>,
    a: A,
    b: B,
    c: C,
    context: MutableDiagnosticContext,
    positioningStrategy: SourceElementPositioningStrategy? = null
) {
    context.withSuppressedDiagnostics(element) {
        reportOn(element.source, factory, a, b, c, positioningStrategy)
    }
}

fun <A : Any, B : Any, C : Any, D : Any> DiagnosticReporter.reportOnWithSuppression(
    element: FirAnnotationContainer,
    factory: KtDiagnosticFactory4<A, B, C, D>,
    a: A,
    b: B,
    c: C,
    d: D,
    context: MutableDiagnosticContext,
    positioningStrategy: SourceElementPositioningStrategy? = null
) {
    context.withSuppressedDiagnostics(element) {
        reportOn(element.source, factory, a, b, c, d, positioningStrategy)
    }
}

@OptIn(SymbolInternals::class)
inline fun <reified C : MutableDiagnosticContext> C.withSuppressedDiagnostics(
    symbol: FirBasedSymbol<*>,
    f: C.() -> Unit
) = withSuppressedDiagnostics(symbol.fir, f)

inline fun <reified C : MutableDiagnosticContext> C.withSuppressedDiagnostics(
    annotationContainer: FirAnnotationContainer,
    f: C.() -> Unit
) {
    val arguments = AbstractDiagnosticCollector.getDiagnosticsSuppressedForContainer(annotationContainer)
    if (arguments != null) {
        (addSuppressedDiagnostics(
            arguments,
            allInfosSuppressed = AbstractDiagnosticCollector.SUPPRESS_ALL_INFOS in arguments,
            allWarningsSuppressed = AbstractDiagnosticCollector.SUPPRESS_ALL_WARNINGS in arguments,
            allErrorsSuppressed = AbstractDiagnosticCollector.SUPPRESS_ALL_ERRORS in arguments
        ) as C).f(
        )
        return
    }
    this.f()
}

