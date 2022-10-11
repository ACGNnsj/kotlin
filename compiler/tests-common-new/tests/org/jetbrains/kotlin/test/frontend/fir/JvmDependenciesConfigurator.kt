/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.frontend.fir

import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
import org.jetbrains.kotlin.cli.jvm.config.jvmModularRoots
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.fir.DependencyListForCliModule
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices

class JvmDependenciesConfigurator(
    module: TestModule,
    testServices: TestServices,
    val configuration: CompilerConfiguration
) : DependenciesConfigurator(module, testServices) {
    override fun configureDependencies(builder: DependencyListForCliModule.Builder) {
        builder.dependencies(configuration.jvmModularRoots.map { it.toPath() })
        builder.dependencies(configuration.jvmClasspathRoots.map { it.toPath() })

        builder.friendDependencies(configuration[JVMConfigurationKeys.FRIEND_PATHS] ?: emptyList())
    }
}