/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.frontend.fir

import org.jetbrains.kotlin.fir.DependencyListForCliModule
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices

abstract class DependenciesConfigurator(val module: TestModule, val testServices: TestServices) {
    val moduleInfoProvider = testServices.firModuleInfoProvider

    fun buildDependencyList(): DependencyListForCliModule {
        val analyzerServices = module.targetPlatform.getAnalyzerServices()
        return DependencyListForCliModule.build(Name.identifier(module.name), module.targetPlatform, analyzerServices) {
            configureDependencies(this)
            sourceDependencies(moduleInfoProvider.getRegularDependentSourceModules(module))
            sourceFriendsDependencies(moduleInfoProvider.getDependentFriendSourceModules(module))
            sourceDependsOnDependencies(moduleInfoProvider.getDependentDependsOnSourceModules(module))
        }
    }

    protected abstract fun configureDependencies(builder: DependencyListForCliModule.Builder)
}