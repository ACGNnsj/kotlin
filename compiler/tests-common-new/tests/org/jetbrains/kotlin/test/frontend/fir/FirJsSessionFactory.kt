/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.frontend.fir

import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.analysis.FirOverridesBackwardCompatibilityHelper
import org.jetbrains.kotlin.fir.checkers.registerJsCheckers
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.java.FirProjectSessionProvider
import org.jetbrains.kotlin.fir.resolve.calls.ConeCallConflictResolverFactory
import org.jetbrains.kotlin.fir.resolve.providers.impl.FirBuiltinSymbolProvider
import org.jetbrains.kotlin.fir.resolve.providers.impl.FirCloneableSymbolProvider
import org.jetbrains.kotlin.fir.scopes.FirKotlinScopeProvider
import org.jetbrains.kotlin.fir.scopes.FirPlatformClassMapper
import org.jetbrains.kotlin.fir.session.FirAbstractSessionFactory
import org.jetbrains.kotlin.fir.session.FirSessionConfigurator
import org.jetbrains.kotlin.fir.session.KlibBasedSymbolProvider
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.ir.backend.js.jsResolveLibraries
import org.jetbrains.kotlin.ir.backend.js.resolverLogger
import org.jetbrains.kotlin.js.config.JSConfigurationKeys
import org.jetbrains.kotlin.library.resolver.KotlinResolvedLibrary
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.TestServices

object FirJsSessionFactory : FirAbstractSessionFactory() {
    fun createLibrarySession(
        mainModuleName: Name,
        sessionProvider: FirProjectSessionProvider,
        dependencyListForCliModule: DependencyListForCliModule,
        module: TestModule,
        testServices: TestServices,
        configuration: CompilerConfiguration,
        languageVersionSettings: LanguageVersionSettings,
    ): FirSession {
        val moduleDataProvider = dependencyListForCliModule.moduleDataProvider
        return createLibrarySession(
            mainModuleName,
            sessionProvider,
            moduleDataProvider,
            languageVersionSettings,
            null,
            createKotlinScopeProvider = { FirKotlinScopeProvider { _, declaredMemberScope, _, _ -> declaredMemberScope } },
            createProviders = { session, builtinsModuleData, kotlinScopeProvider ->
                listOf(
                    FirBuiltinSymbolProvider(session, builtinsModuleData, kotlinScopeProvider),
                    FirCloneableSymbolProvider(session, builtinsModuleData, kotlinScopeProvider),
                ) + resolveJsLibraries(module, testServices, configuration).map {
                    KlibBasedSymbolProvider(session, moduleDataProvider, kotlinScopeProvider, it)
                }
            })
    }

    private fun resolveJsLibraries(
        module: TestModule,
        testServices: TestServices,
        configuration: CompilerConfiguration
    ): List<KotlinResolvedLibrary> {
        val dependencyConfigurator = JsDependenciesConfigurator(module, testServices)
        val (runtimeKlibsPaths, transitiveLibraries, friendLibraries) = dependencyConfigurator.getJsDependencies()
        val paths = runtimeKlibsPaths + transitiveLibraries.map { it.path } + friendLibraries.map { it.path }
        val repositories = configuration[JSConfigurationKeys.REPOSITORIES] ?: emptyList()
        val logger = configuration.resolverLogger
        return jsResolveLibraries(paths, repositories, logger).getFullResolvedList()
    }

    fun createModuleBasedSession(
        moduleData: FirModuleData,
        sessionProvider: FirProjectSessionProvider,
        extensionRegistrars: List<FirExtensionRegistrar>,
        languageVersionSettings: LanguageVersionSettings = LanguageVersionSettingsImpl.DEFAULT,
        lookupTracker: LookupTracker?,
        init: FirSessionConfigurator.() -> Unit
    ): FirSession {
        return createModuleBasedSession(
            moduleData,
            sessionProvider,
            extensionRegistrars,
            languageVersionSettings,
            lookupTracker,
            null,
            init,
            registerExtraComponents = { it.registerJsSpecificResolveComponents() },
            registerExtraCheckers = { it.registerJsCheckers() },
            createKotlinScopeProvider = { FirKotlinScopeProvider { _, declaredMemberScope, _, _ -> declaredMemberScope } },
            createProviders = { _, _, symbolProvider, generatedSymbolsProvider, dependenciesSymbolProvider ->
                listOfNotNull(
                    symbolProvider,
                    generatedSymbolsProvider,
                    dependenciesSymbolProvider,
                )
            }
        )
    }

    @OptIn(SessionConfiguration::class)
    fun FirSession.registerJsSpecificResolveComponents() {
        register(FirVisibilityChecker::class, FirVisibilityChecker.Default)
        register(ConeCallConflictResolverFactory::class, JsCallConflictResolverFactory)
        register(FirPlatformClassMapper::class, FirPlatformClassMapper.Default)
        register(FirOverridesBackwardCompatibilityHelper::class, FirOverridesBackwardCompatibilityHelper.Default())
    }
}