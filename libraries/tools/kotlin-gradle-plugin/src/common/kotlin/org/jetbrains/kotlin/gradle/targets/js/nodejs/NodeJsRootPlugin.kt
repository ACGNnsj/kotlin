/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.nodejs

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.provider.Provider
import org.jetbrains.kotlin.gradle.targets.js.MultiplePluginDeclarationDetector
import org.jetbrains.kotlin.gradle.targets.js.npm.GradleNodeModulesCache
import org.jetbrains.kotlin.gradle.targets.js.npm.KotlinNpmResolutionManager
import org.jetbrains.kotlin.gradle.targets.js.npm.UsesKotlinNpmResolutionManager
import org.jetbrains.kotlin.gradle.targets.js.npm.resolver.KotlinRootNpmResolver
import org.jetbrains.kotlin.gradle.targets.js.npm.resolver.PACKAGE_JSON_UMBRELLA_TASK_NAME
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmCachesSetup
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.tasks.CleanDataTask
import org.jetbrains.kotlin.gradle.tasks.registerTask
import org.jetbrains.kotlin.gradle.tasks.withType
import org.jetbrains.kotlin.gradle.utils.*
import org.jetbrains.kotlin.gradle.utils.SingleActionPerProject
import org.jetbrains.kotlin.gradle.utils.castIsolatedKotlinPluginClassLoaderAware
import org.jetbrains.kotlin.gradle.utils.doNotTrackStateCompat
import org.jetbrains.kotlin.gradle.utils.markResolvable
import org.jetbrains.kotlin.gradle.utils.providerWithLazyConvention

open class NodeJsRootPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        MultiplePluginDeclarationDetector.detect(project)

        project.plugins.apply(BasePlugin::class.java)

        check(project == project.rootProject) {
            "NodeJsRootPlugin can be applied only to root project"
        }

        val nodeJs = project.extensions.create(
            NodeJsRootExtension.EXTENSION_NAME,
            NodeJsRootExtension::class.java,
            project
        )

        val setupTask = project.registerTask<NodeJsSetupTask>(NodeJsSetupTask.NAME) {
            it.group = TASKS_GROUP_NAME
            it.description = "Download and install a local node/npm version"
            it.configuration = project.provider {
                project.configurations.detachedConfiguration(project.dependencies.create(it.ivyDependency))
                    .markResolvable()
                    .also { conf -> conf.isTransitive = false }
            }
        }

        val gradleNodeModulesProvider: Provider<GradleNodeModulesCache> = GradleNodeModulesCache.registerIfAbsent(
            project,
            project.projectDir,
            nodeJs.nodeModulesGradleCacheDir
        )

        val setupFileHasherTask = project.registerTask<KotlinNpmCachesSetup>(KotlinNpmCachesSetup.NAME) {
            it.description = "Setup file hasher for caches"

            it.gradleNodeModules.set(gradleNodeModulesProvider)
        }

        val npmInstall = project.registerTask<KotlinNpmInstallTask>(KotlinNpmInstallTask.NAME) {
            it.dependsOn(setupTask)
            it.dependsOn(setupFileHasherTask)
            it.group = TASKS_GROUP_NAME
            it.description = "Find, download and link NPM dependencies and projects"

            it.onlyIfCompat("No package.json files for install") { task ->
                task as KotlinNpmInstallTask
                task.preparedFiles.all { file ->
                    file.exists()
                }
            }

            it.doNotTrackStateCompat("NPM package manager track by its own")
        }

        project.registerTask<Task>(PACKAGE_JSON_UMBRELLA_TASK_NAME)

        nodeJs.resolver = KotlinRootNpmResolver(
            project.name,
            project.version.toString(),
            TasksRequirements(),
            nodeJs.versions,
            nodeJs.projectPackagesDir,
            nodeJs.rootProjectDir,
        )

        val objectFactory = project.objects

        // TODO: Could we use common approach with build services to KotlinNpmResolutionManager?
        val npmResolutionManager = project.gradle.sharedServices.registerIfAbsent(
            KotlinNpmResolutionManager::class.java.name,
            KotlinNpmResolutionManager::class.java
        ) {
            it.parameters.resolution.set(
                objectFactory.providerWithLazyConvention {
                    nodeJs.resolver.close()
                }
            )
            it.parameters.packageJsonHandlers.set(
                objectFactory.providerWithLazyConvention {
                    nodeJs.resolver.compilations.associate { compilation ->
                        "${compilation.project.path}:${compilation.disambiguatedName}" to compilation.packageJsonHandlers
                    }
                }
            )
            it.parameters.gradleNodeModulesProvider.set(gradleNodeModulesProvider)
        }

        YarnPlugin.apply(project)

        npmInstall.configure {
            it.npmResolutionManager.value(npmResolutionManager).disallowChanges()
        }

        project.tasks.register("node" + CleanDataTask.NAME_SUFFIX, CleanDataTask::class.java) {
            it.cleanableStoreProvider = project.provider { nodeJs.requireConfigured().cleanableStore }
            it.group = TASKS_GROUP_NAME
            it.description = "Clean unused local node version"
        }
    }

    companion object {
        const val TASKS_GROUP_NAME: String = "nodeJs"

        fun apply(rootProject: Project): NodeJsRootExtension {
            check(rootProject == rootProject.rootProject)
            rootProject.plugins.apply(NodeJsRootPlugin::class.java)
            return rootProject.extensions.getByName(NodeJsRootExtension.EXTENSION_NAME) as NodeJsRootExtension
        }

        val Project.kotlinNodeJsExtension: NodeJsRootExtension
            get() = extensions.getByName(NodeJsRootExtension.EXTENSION_NAME).castIsolatedKotlinPluginClassLoaderAware()

        private val Project.gradleNodeModules
            get() = GradleNodeModulesCache.registerIfAbsent(
                this,
                null,
                null
            )

        val Project.kotlinNpmResolutionManager: Provider<KotlinNpmResolutionManager>
            get() {
                val npmResolutionManager = project.gradle.sharedServices.registerIfAbsent(
                    KotlinNpmResolutionManager::class.java.name,
                    KotlinNpmResolutionManager::class.java
                ) {
                    error("Must be already registered")
                }

                SingleActionPerProject.run(project, UsesKotlinNpmResolutionManager::class.java.name) {
                    project.tasks.withType<UsesKotlinNpmResolutionManager>().configureEach { task ->
                        task.usesService(npmResolutionManager)
                        task.usesService(gradleNodeModules)
                    }
                }

                return npmResolutionManager
            }
    }
}
