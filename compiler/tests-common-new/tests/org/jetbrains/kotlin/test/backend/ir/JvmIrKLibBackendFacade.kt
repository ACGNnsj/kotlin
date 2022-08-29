/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.backend.ir

import org.jetbrains.kotlin.backend.common.IrActualizer
import org.jetbrains.kotlin.test.model.*
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.dependencyProvider

class JvmIrKLibBackendFacade(
    val testServices: TestServices,
) : AbstractTestFacade<KLibArtifact, BinaryArtifacts.Jvm>() {
    private val jvmFacadeHelper = JvmBackendFacadeHelper(testServices)

    override val inputKind = KLibKinds.KLib
    override val outputKind = ArtifactKinds.Jvm

    override fun shouldRunAnalysis(module: TestModule): Boolean {
        val incomingDependencies = testServices.dependencyProvider.getIncomingDependencies(module)
        return incomingDependencies.none { it.relation == DependencyRelation.DependsOnDependency }
    }

    override fun transform(module: TestModule, inputArtifact: KLibArtifact): BinaryArtifacts.Jvm? {
        require(inputArtifact is KLibArtifact.JvmIrKLibArtifact) {
            "JvmIrKLibBackendFacade expects KLibArtifact.JvmIrKLibArtifact as input"
        }

        val dependencyProvider = testServices.dependencyProvider
        val dependencies = module.dependsOnDependencies.map { dependency ->
            val testModule = dependencyProvider.getTestModule(dependency.moduleName)
            val artifact = dependencyProvider.getArtifact(testModule, KLibKinds.KLib)
            artifact.irModuleFragment
        }
        IrActualizer.actualize(inputArtifact.irModuleFragment, dependencies)

        return jvmFacadeHelper.transform(
            inputArtifact.state,
            inputArtifact.codegenFactory,
            inputArtifact.backendInput,
            inputArtifact.sourceFiles,
            module
        )
    }
}