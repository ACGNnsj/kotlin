/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.pipeline

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.jvm.serialization.JvmIdSignatureDescriptor
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.backend.*
import org.jetbrains.kotlin.fir.backend.jvm.Fir2IrJvmSpecialAnnotationSymbolProvider
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmKotlinMangler
import org.jetbrains.kotlin.fir.backend.jvm.FirJvmVisibilityConverter
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.languageVersionSettings
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.ir.backend.jvm.serialization.JvmDescriptorMangler
import org.jetbrains.kotlin.ir.backend.jvm.serialization.JvmIrMangler
import org.jetbrains.kotlin.ir.declarations.impl.IrFactoryImpl

data class FirResult(
    val platformOutput: ModuleCompilerAnalyzedOutput,
    val commonOutput: ModuleCompilerAnalyzedOutput?
)

data class ModuleCompilerAnalyzedOutput(
    val session: FirSession,
    val scopeSession: ScopeSession,
    val fir: List<FirFile>
)

fun ModuleCompilerAnalyzedOutput.convertToIr(
    fir2IrExtensions: Fir2IrExtensions,
    irGeneratorExtensions: Collection<IrGenerationExtension>,
    linkViaSignatures: Boolean,
    extraFir: List<FirFile> = emptyList(),
    dependentComponents: List<Fir2IrComponents> = emptyList()
): Fir2IrResult {
    if (linkViaSignatures) {
        val signaturer = JvmIdSignatureDescriptor(mangler = JvmDescriptorMangler(mainDetector = null))
        return Fir2IrConverter.createModuleFragmentWithSignaturesIfNeeded(
            session, scopeSession, fir + extraFir,
            session.languageVersionSettings, signaturer, fir2IrExtensions,
            FirJvmKotlinMangler(session),
            JvmIrMangler, IrFactoryImpl, FirJvmVisibilityConverter,
            Fir2IrJvmSpecialAnnotationSymbolProvider(),
            irGeneratorExtensions, true,
            dependentComponents
        )
    } else {
        return Fir2IrConverter.createModuleFragmentWithoutSignatures(
            session, scopeSession, fir + extraFir,
            session.languageVersionSettings, fir2IrExtensions,
            FirJvmKotlinMangler(session),
            JvmIrMangler, IrFactoryImpl, FirJvmVisibilityConverter,
            Fir2IrJvmSpecialAnnotationSymbolProvider(),
            irGeneratorExtensions
        )
    }
}
