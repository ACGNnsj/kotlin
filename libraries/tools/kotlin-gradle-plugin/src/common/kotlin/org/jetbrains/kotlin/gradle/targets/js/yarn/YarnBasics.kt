/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.yarn

import org.gradle.api.logging.Logger
import org.gradle.internal.service.ServiceRegistry
import org.jetbrains.kotlin.gradle.internal.execWithProgress
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmApi
import org.jetbrains.kotlin.gradle.targets.js.npm.NpmEnvironment
import org.jetbrains.kotlin.gradle.targets.js.npm.YarnEnvironment
import java.io.File

abstract class YarnBasics : NpmApi {

    fun yarnExec(
        services: ServiceRegistry,
        logger: Logger,
        nodeJs: NpmEnvironment,
        yarn: YarnEnvironment,
        dir: File,
        description: String,
        args: List<String>
    ) {
        services.execWithProgress(description) { exec ->
            val arguments = args
                .plus(
                    if (logger.isDebugEnabled) "--verbose" else ""
                )
                .plus(
                    if (yarn.ignoreScripts && !isBerryInstalled()) "--ignore-scripts" else ""
                ).filter { it.isNotEmpty() }

            val nodeExecutable = nodeJs.nodeExecutable
            if (!yarn.ignoreScripts) {
                val nodePath = if (nodeJs.isWindows) {
                    File(nodeExecutable).parent
                } else {
                    nodeExecutable
                }
                exec.environment(
                    "PATH",
                    "$nodePath${File.pathSeparator}${System.getenv("PATH")}"
                )
            }

            val command = yarn.executable
            if (yarn.standalone) {
                exec.executable = command
                exec.args = arguments
            } else {
                exec.executable = nodeExecutable
                exec.args = listOf(command) + arguments
            }

            exec.workingDir = dir
        }

    }

    fun isBerryInstalled(): Boolean {
        var flag = true
        runCatching {
            val osName = System.getProperty("os.name")
            val command = if (osName != null && osName.startsWith("Windows")) {
                arrayOf("cmd", "/c", "yarn --version")
            } else {
                arrayOf("/bin/sh", "-c", "yarn --version")
            }
            val process = Runtime.getRuntime().exec(command)
            val reader = process.inputStream.bufferedReader()
            val version = reader.readText()
            flag = version.isNotEmpty() && !version.startsWith("1.")
        }
        return flag
    }
}
