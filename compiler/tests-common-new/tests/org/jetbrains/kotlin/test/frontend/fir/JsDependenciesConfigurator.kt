/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.frontend.fir

import org.jetbrains.kotlin.fir.DependencyListForCliModule
import org.jetbrains.kotlin.test.model.DependencyRelation
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.configuration.JsEnvironmentConfigurator
import java.io.File
import java.nio.file.Paths

class JsDependenciesConfigurator(module: TestModule, testServices: TestServices) : DependenciesConfigurator(module, testServices) {
    override fun configureDependencies(builder: DependencyListForCliModule.Builder) {
        val (runtimeKlibsPaths, transitiveLibraries, friendLibraries) = getJsDependencies()

        builder.dependencies(runtimeKlibsPaths.map { Paths.get(it).toAbsolutePath() })
        builder.dependencies(transitiveLibraries.map { it.toPath().toAbsolutePath() })

        builder.friendDependencies(friendLibraries.map { it.toPath().toAbsolutePath() })
    }

    fun getJsDependencies(): Triple<List<String>, List<File>, List<File>> {
        val runtimeKlibsPaths = JsEnvironmentConfigurator.getRuntimePathsForModule(module, testServices)
        val transitiveLibraries = JsEnvironmentConfigurator.getKlibDependencies(module, testServices, DependencyRelation.RegularDependency)
        val friendLibraries = JsEnvironmentConfigurator.getKlibDependencies(module, testServices, DependencyRelation.FriendDependency)
        return Triple(runtimeKlibsPaths, transitiveLibraries, friendLibraries)
    }
}