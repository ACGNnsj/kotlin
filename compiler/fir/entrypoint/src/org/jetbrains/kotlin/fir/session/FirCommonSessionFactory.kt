/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.session
import org.jetbrains.kotlin.config.JvmAnalysisFlags
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.analysis.FirOverridesBackwardCompatibilityHelper
import org.jetbrains.kotlin.fir.checkers.registerJsCheckers
import org.jetbrains.kotlin.fir.checkers.registerJvmCheckers
import org.jetbrains.kotlin.fir.checkers.registerNativeCheckers
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.deserialization.SingleModuleDataProvider
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.java.FirJvmDefaultModeComponent
import org.jetbrains.kotlin.fir.java.FirProjectSessionProvider
import org.jetbrains.kotlin.fir.java.deserialization.JvmClassFileBasedSymbolProvider
import org.jetbrains.kotlin.fir.java.deserialization.OptionalAnnotationClassesProvider
import org.jetbrains.kotlin.fir.java.enhancement.FirAnnotationTypeQualifierResolver
import org.jetbrains.kotlin.fir.java.enhancement.FirEnhancedSymbolsStorage
import org.jetbrains.kotlin.fir.resolve.calls.ConeCallConflictResolverFactory
import org.jetbrains.kotlin.fir.resolve.providers.impl.FirBuiltinSymbolProvider
import org.jetbrains.kotlin.fir.resolve.providers.impl.FirCloneableSymbolProvider
import org.jetbrains.kotlin.fir.scopes.FirKotlinScopeProvider
import org.jetbrains.kotlin.fir.scopes.FirPlatformClassMapper
import org.jetbrains.kotlin.fir.session.environment.AbstractProjectEnvironment
import org.jetbrains.kotlin.fir.session.environment.AbstractProjectFileSearchScope
import org.jetbrains.kotlin.incremental.components.EnumWhenTracker
import org.jetbrains.kotlin.incremental.components.LookupTracker
import org.jetbrains.kotlin.load.kotlin.PackagePartProvider
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.jvm.modules.JavaModuleResolver

object FirCommonSessionFactory : FirAbstractSessionFactory() {
    fun createLibrarySession(
        mainModuleName: Name,
        sessionProvider: FirProjectSessionProvider,
        dependencyList: DependencyListForCliModule,
        projectEnvironment: AbstractProjectEnvironment,
        scope: AbstractProjectFileSearchScope,
        packagePartProvider: PackagePartProvider,
        languageVersionSettings: LanguageVersionSettings,
    ): FirSession {
        return createLibrarySession(
            mainModuleName,
            sessionProvider,
            dependencyList.moduleDataProvider,
            languageVersionSettings,
            registerExtraComponents = { it.registerExtraComponentsForLibrary(projectEnvironment.getJavaModuleResolver()) },
            createKotlinScopeProvider = { FirKotlinScopeProvider { _, declaredMemberScope, _, _ -> declaredMemberScope } },
            createProviders = { session, builtinsModuleData, kotlinScopeProvider ->
                listOf(
                    JvmClassFileBasedSymbolProvider(
                        session,
                        dependencyList.moduleDataProvider,
                        kotlinScopeProvider,
                        packagePartProvider,
                        projectEnvironment.getKotlinClassFinder(scope),
                        projectEnvironment.getFirJavaFacade(session, dependencyList.moduleDataProvider.allModuleData.last(), scope)
                    ),
                    FirBuiltinSymbolProvider(session, builtinsModuleData, kotlinScopeProvider),
                    FirCloneableSymbolProvider(session, builtinsModuleData, kotlinScopeProvider),
                    OptionalAnnotationClassesProvider(session, dependencyList.moduleDataProvider, kotlinScopeProvider, packagePartProvider)
                )
            }
        )
    }

    fun createModuleBasedSession(
        moduleData: FirModuleData,
        sessionProvider: FirProjectSessionProvider,
        projectEnvironment: AbstractProjectEnvironment,
        incrementalCompilationContext: IncrementalCompilationContext?,
        extensionRegistrars: List<FirExtensionRegistrar>,
        languageVersionSettings: LanguageVersionSettings = LanguageVersionSettingsImpl.DEFAULT,
        lookupTracker: LookupTracker? = null,
        enumWhenTracker: EnumWhenTracker? = null,
        init: FirSessionConfigurator.() -> Unit = {}
    ): FirSession {
        return createModuleBasedSession(
            moduleData,
            sessionProvider,
            extensionRegistrars,
            languageVersionSettings,
            lookupTracker,
            enumWhenTracker,
            init,
            registerExtraComponents = {
                it.registerExtraComponentsForLibrary(projectEnvironment.getJavaModuleResolver())
                it.registerExtraComponentsForModuleBased()
            },
            registerExtraCheckers = {
                it.registerJvmCheckers()
                it.registerJsCheckers()
                it.registerNativeCheckers()
            },
            createKotlinScopeProvider = { FirKotlinScopeProvider { _, declaredMemberScope, _, _ -> declaredMemberScope } },
            createProviders = { session, kotlinScopeProvider, symbolProvider, generatedSymbolsProvider, dependenciesSymbolProvider ->
                var symbolProviderForBinariesFromIncrementalCompilation: JvmClassFileBasedSymbolProvider? = null
                var optionalAnnotationClassesProviderForBinariesFromIncrementalCompilation: OptionalAnnotationClassesProvider? = null
                incrementalCompilationContext?.let {
                    if (it.precompiledBinariesPackagePartProvider != null && it.precompiledBinariesFileScope != null) {
                        val moduleDataProvider = SingleModuleDataProvider(moduleData)
                        symbolProviderForBinariesFromIncrementalCompilation =
                            JvmClassFileBasedSymbolProvider(
                                session,
                                moduleDataProvider,
                                kotlinScopeProvider,
                                it.precompiledBinariesPackagePartProvider,
                                projectEnvironment.getKotlinClassFinder(it.precompiledBinariesFileScope),
                                projectEnvironment.getFirJavaFacade(session, moduleData, it.precompiledBinariesFileScope),
                                defaultDeserializationOrigin = FirDeclarationOrigin.Precompiled
                            )
                        optionalAnnotationClassesProviderForBinariesFromIncrementalCompilation =
                            OptionalAnnotationClassesProvider(
                                session,
                                moduleDataProvider,
                                kotlinScopeProvider,
                                it.precompiledBinariesPackagePartProvider,
                                defaultDeserializationOrigin = FirDeclarationOrigin.Precompiled
                            )
                    }
                }

                listOfNotNull(
                    symbolProvider,
                    *(incrementalCompilationContext?.previousFirSessionsSymbolProviders?.toTypedArray() ?: emptyArray()),
                    symbolProviderForBinariesFromIncrementalCompilation,
                    generatedSymbolsProvider,
                    dependenciesSymbolProvider,
                    optionalAnnotationClassesProviderForBinariesFromIncrementalCompilation,
                )
            }
        )
    }

    @OptIn(SessionConfiguration::class)
    fun FirSession.registerExtraComponentsForLibrary(javaModuleResolver: JavaModuleResolver) {
        val jsr305State = languageVersionSettings.getFlag(JvmAnalysisFlags.javaTypeEnhancementState)
        register(FirAnnotationTypeQualifierResolver::class, FirAnnotationTypeQualifierResolver(this, jsr305State, javaModuleResolver))
        register(FirEnhancedSymbolsStorage::class, FirEnhancedSymbolsStorage(this))
        register(
            FirJvmDefaultModeComponent::class,
            FirJvmDefaultModeComponent(languageVersionSettings.getFlag(JvmAnalysisFlags.jvmDefaultMode))
        )
    }

    @OptIn(SessionConfiguration::class)
    fun FirSession.registerExtraComponentsForModuleBased() {
        register(FirVisibilityChecker::class, FirVisibilityChecker.Default)
        register(ConeCallConflictResolverFactory::class, CommonCallConflictResolverFactory)
        register(FirPlatformClassMapper::class, FirPlatformClassMapper.Default)
        register(FirOverridesBackwardCompatibilityHelper::class, FirOverridesBackwardCompatibilityHelper.Default())
    }
}