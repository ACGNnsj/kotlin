/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.cli.common.arguments

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.*

class K2NativeCompilerArguments : CommonCompilerArguments() {
    // First go the options interesting to the general public.
    // Prepend them with a single dash.
    // Keep the list lexically sorted.

    @Argument(value = "-enable-assertions", deprecatedName = "-enable_assertions", shortName = "-ea", description = "Enable runtime assertions in generated code")
    var enableAssertions: Boolean = false

    @Argument(value = "-g", description = "Enable emitting debug information")
    var debug: Boolean = false

    @Argument(
        value = "-generate-test-runner",
        deprecatedName = "-generate_test_runner",
        shortName = "-tr", description = "Produce a runner for unit tests"
    )
    var generateTestRunner = false

    @Argument(
        value = "-generate-worker-test-runner",
        shortName = "-trw",
        description = "Produce a worker runner for unit tests"
    )
    var generateWorkerTestRunner = false

    @Argument(
        value = "-generate-no-exit-test-runner",
        shortName = "-trn",
        description = "Produce a runner for unit tests not forcing exit"
    )
    var generateNoExitTestRunner = false

    @Argument(value="-include-binary", deprecatedName = "-includeBinary", shortName = "-ib", valueDescription = "<path>", description = "Pack external binary within the klib")
    var includeBinaries: Array<String>? = null

    @Argument(value = "-library", shortName = "-l", valueDescription = "<path>", description = "Link with the library", delimiter = "")
    var libraries: Array<String>? = null

    @Argument(value = "-library-version", shortName = "-lv", valueDescription = "<version>", description = "Set library version")
    var libraryVersion: String? = null

    @Argument(value = "-list-targets", deprecatedName = "-list_targets", description = "List available hardware targets")
    var listTargets: Boolean = false

    @Argument(value = "-manifest", valueDescription = "<path>", description = "Provide a maniferst addend file")
    var manifestFile: String? = null

    @Argument(value="-memory-model", valueDescription = "<model>", description = "Memory model to use, 'strict' and 'experimental' are currently supported")
    var memoryModel: String? = null

    @GradleOption(
        value = DefaultValue.STRING_NULL_DEFAULT,
        gradleInputType = GradleInputTypes.INPUT
    )
    @Argument(
        value = "-module-name",
        deprecatedName = "-module_name",
        valueDescription = "<name>",
        description = "Specify a name for the compilation module"
    )
    var moduleName: String? = null

    @Argument(
        value = "-native-library",
        deprecatedName = "-nativelibrary",
        shortName = "-nl",
        valueDescription = "<path>",
        description = "Include the native bitcode library", delimiter = ""
    )
    var nativeLibraries: Array<String>? = null

    @Argument(value = "-no-default-libs", deprecatedName = "-nodefaultlibs", description = "Don't link the libraries from dist/klib automatically")
    var nodefaultlibs: Boolean = false

    @Argument(value = "-no-endorsed-libs", description = "Don't link the endorsed libraries from dist automatically")
    var noendorsedlibs: Boolean = false

    @Argument(value = "-nomain", description = "Assume 'main' entry point to be provided by external libraries")
    var nomain: Boolean = false

    @Argument(value = "-nopack", description = "Don't pack the library into a klib file")
    var nopack: Boolean = false

    @Argument(value="-linker-options", deprecatedName = "-linkerOpts", valueDescription = "<arg>", description = "Pass arguments to linker", delimiter = " ")
    var linkerArguments: Array<String>? = null

    @Argument(value="-linker-option", valueDescription = "<arg>", description = "Pass argument to linker", delimiter = "")
    var singleLinkerArguments: Array<String>? = null

    @Argument(value = "-nostdlib", description = "Don't link with stdlib")
    var nostdlib: Boolean = false

    @Argument(value = "-opt", description = "Enable optimizations during compilation")
    var optimization: Boolean = false

    @Argument(value = "-output", shortName = "-o", valueDescription = "<name>", description = "Output name")
    var outputName: String? = null

    @Argument(value = "-entry", shortName = "-e", valueDescription = "<name>", description = "Qualified entry point name")
    var mainPackage: String? = null

    @Argument(
        value = "-produce", shortName = "-p",
        valueDescription = "{program|static|dynamic|framework|library|bitcode}",
        description = "Specify output file kind"
    )
    var produce: String? = null

    @Argument(value = "-repo", shortName = "-r", valueDescription = "<path>", description = "Library search path")
    var repositories: Array<String>? = null

    @Argument(value = "-target", valueDescription = "<target>", description = "Set hardware target")
    var target: String? = null

    // The rest of the options are only interesting to the developers.
    // Make sure to prepend them with -X.
    // Keep the list lexically sorted.

    @Argument(
        value = "-Xbundle-id",
        valueDescription = "<id>",
        description = "Bundle ID to be set in Info.plist of a produced framework. Deprecated. Please use -Xbinary=bundleId=<id>."
    )
    var bundleId: String? = null

    @Argument(
        value = "-Xcache-directory",
        valueDescription = "<path>",
        description = "Path to the directory containing caches",
        delimiter = ""
    )
    var cacheDirectories: Array<String>? = null

    @Argument(
        value = CACHED_LIBRARY,
        valueDescription = "<library path>,<cache path>",
        description = "Comma-separated paths of a library and its cache",
        delimiter = ""
    )
    var cachedLibraries: Array<String>? = null

    @Argument(
        value = "-Xauto-cache-from",
        valueDescription = "<path>",
        description = "Path to the root directory from which dependencies are to be cached automatically.\n" +
                "By default caches will be placed into the kotlin-native system cache directory.",
        delimiter = ""
    )
    var autoCacheableFrom: Array<String>? = null

    @Argument(
        value = "-Xauto-cache-dir",
        valueDescription = "<path>",
        description = "Path to the directory where to put caches for auto-cacheable dependencies",
        delimiter = ""
    )
    var autoCacheDir: String? = null

    @Argument(value="-Xcheck-dependencies", deprecatedName = "--check_dependencies", description = "Check dependencies and download the missing ones")
    var checkDependencies: Boolean = false

    @Argument(value = EMBED_BITCODE_FLAG, description = "Embed LLVM IR bitcode as data")
    var embedBitcode: Boolean = false

    @Argument(value = EMBED_BITCODE_MARKER_FLAG, description = "Embed placeholder LLVM IR data as a marker")
    var embedBitcodeMarker: Boolean = false

    @Argument(value = "-Xemit-lazy-objc-header", description = "")
    var emitLazyObjCHeader: String? = null

    @Argument(
        value = "-Xexport-library",
        valueDescription = "<path>",
        description = "A library to be included into produced framework API.\n" +
                "Must be one of libraries passed with '-library'",
        delimiter = ""
    )
    var exportedLibraries: Array<String>? = null

    @Argument(
        value = "-Xexternal-dependencies",
        valueDescription = "<path>",
        description = "Path to the file containing external dependencies.\n" +
                "External dependencies are required for verbose output in case of IR linker errors,\n" +
                "but they do not affect compilation at all."
    )
    var externalDependencies: String? = null

    @Argument(value="-Xfake-override-validator", description = "Enable IR fake override validator")
    var fakeOverrideValidator: Boolean = false

    @Argument(
        value = "-Xframework-import-header",
        valueDescription = "<header>",
        description = "Add additional header import to framework header"
    )
    var frameworkImportHeaders: Array<String>? = null

    @Argument(
        value = "-Xadd-light-debug",
        valueDescription = "{disable|enable}",
        description = "Add light debug information for optimized builds. This option is skipped in debug builds.\n" +
                "It's enabled by default on Darwin platforms where collected debug information is stored in .dSYM file.\n" +
                "Currently option is disabled by default on other platforms."
    )
    var lightDebugString: String? = null

    // TODO: remove after 1.4 release.
    @Argument(value = "-Xg0", description = "Add light debug information. Deprecated option. Please use instead -Xadd-light-debug=enable")
    var lightDebugDeprecated: Boolean = false

    @Argument(
        value = "-Xg-generate-debug-trampoline",
        valueDescription = "{disable|enable}",
        description = """generates trampolines to make debugger breakpoint resolution more accurate (inlines, when, etc.)"""
    )
    var generateDebugTrampolineString: String? = null


    @Argument(
        value = ADD_CACHE,
        valueDescription = "<path>",
        description = "Path to the library to be added to cache",
        delimiter = ""
    )
    var libraryToAddToCache: String? = null

    @Argument(
        value = "-Xfile-to-cache",
        valueDescription = "<path>",
        description = "Path to file to cache",
        delimiter = ""
    )
    var filesToCache: Array<String>? = null

    @Argument(value = "-Xmake-per-file-cache", description = "Force compiler to produce per-file cache")
    var makePerFileCache: Boolean = false

    @Argument(
        value = "-Xbackend-threads",
        valueDescription = "<N>",
        description = "Run codegen by file in N parallel threads.\n" +
                "0 means use a thread per processor core.\n" +
                "Default value is 1"
    )
    var backendThreads: String = "1"

    @Argument(value = "-Xexport-kdoc", description = "Export KDoc in framework header")
    var exportKDoc: Boolean = false

    @Argument(value = "-Xprint-bitcode", deprecatedName = "--print_bitcode", description = "Print llvm bitcode")
    var printBitCode: Boolean = false

    @Argument(value = "-Xcheck-state-at-external-calls", description = "Check all calls of possibly long external functions are done in Native state")
    var checkExternalCalls: Boolean = false

    @Argument(value = "-Xprint-ir", deprecatedName = "--print_ir", description = "Print IR")
    var printIr: Boolean = false

    @Argument(value = "-Xprint-files", description = "Print files")
    var printFiles: Boolean = false

    @Argument(value="-Xpurge-user-libs", deprecatedName = "--purge_user_libs", description = "Don't link unused libraries even explicitly specified")
    var purgeUserLibs: Boolean = false

    @Argument(value = "-Xruntime", deprecatedName = "--runtime", valueDescription = "<path>", description = "Override standard 'runtime.bc' location")
    var runtimeFile: String? = null

    @Argument(
        value = INCLUDE_ARG,
        valueDescription = "<path>",
        description = "A path to an intermediate library that should be processed in the same manner as source files"
    )
    var includes: Array<String>? = null

    @Argument(
        value = SHORT_MODULE_NAME_ARG,
        valueDescription = "<name>",
        description = "A short name used to denote this library in the IDE and in a generated Objective-C header"
    )
    var shortModuleName: String? = null

    @Argument(value = STATIC_FRAMEWORK_FLAG, description = "Create a framework with a static library instead of a dynamic one")
    var staticFramework: Boolean = false

    @Argument(value = "-Xtemporary-files-dir", deprecatedName = "--temporary_files_dir", valueDescription = "<path>", description = "Save temporary files to the given directory")
    var temporaryFilesDir: String? = null

    @Argument(value = "-Xsave-llvm-ir-after", description = "Save result of Kotlin IR to LLVM IR translation to -Xsave-llvm-ir-directory.")
    var saveLlvmIrAfter: Array<String> = emptyArray()

    @Argument(value = "-Xverify-bitcode", deprecatedName = "--verify_bitcode", description = "Verify llvm bitcode after each method")
    var verifyBitCode: Boolean = false

    @Argument(
        value = "-Xverify-ir",
        valueDescription = "{none|warning|error}",
        description = "IR verification mode (no verification by default)"
    )
    var verifyIr: String? = null

    @Argument(value = "-Xverify-compiler", description = "Verify compiler")
    var verifyCompiler: String? = null

    @Argument(
        value = "-friend-modules",
        valueDescription = "<path>",
        description = "Paths to friend modules"
    )
    var friendModules: String? = null

    /**
     * @see K2MetadataCompilerArguments.refinesPaths
     */
    @Argument(
        value = "-Xrefines-paths",
        valueDescription = "<path>",
        description = "Paths to output directories for refined modules (whose expects this module can actualize)"
    )
    var refinesPaths: Array<String>? = null

    @Argument(value = "-Xdebug-info-version", description = "generate debug info of given version (1, 2)")
    var debugInfoFormatVersion: String = "1" /* command line parser doesn't accept kotlin.Int type */

    @Argument(value = "-Xcoverage", description = "emit coverage")
    var coverage: Boolean = false

    @Argument(
        value = "-Xlibrary-to-cover",
        valueDescription = "<path>",
        description = "Provide code coverage for the given library.\n" +
                "Must be one of libraries passed with '-library'",
        delimiter = ""
    )
    var coveredLibraries: Array<String>? = null

    @Argument(value = "-Xcoverage-file", valueDescription = "<path>", description = "Save coverage information to the given file")
    var coverageFile: String? = null

    @Argument(value = "-Xno-objc-generics", description = "Disable generics support for framework header")
    var noObjcGenerics: Boolean = false

    @Argument(value="-Xoverride-clang-options", valueDescription = "<arg1,arg2,...>", description = "Explicit list of Clang options")
    var clangOptions: Array<String>? = null

    @Argument(value="-Xallocator", valueDescription = "std | mimalloc | custom", description = "Allocator used in runtime")
    var allocator: String? = null

    @Argument(value = "-Xmetadata-klib", description = "Produce a klib that only contains the declarations metadata")
    var metadataKlib: Boolean = false

    @Argument(value = "-Xdebug-prefix-map", valueDescription = "<old1=new1,old2=new2,...>", description = "Remap file source directory paths in debug info")
    var debugPrefixMap: Array<String>? = null

    @Argument(
        value = "-Xpre-link-caches",
        valueDescription = "{disable|enable}",
        description = "Perform caches pre-link"
    )
    var preLinkCaches: String? = null

    // We use `;` as delimiter because properties may contain comma-separated values.
    // For example, target cpu features.
    @Argument(
        value = "-Xoverride-konan-properties",
        valueDescription = "key1=value1;key2=value2;...",
        description = "Override konan.properties.values",
        delimiter = ";"
    )
    var overrideKonanProperties: Array<String>? = null

    @Argument(value="-Xdestroy-runtime-mode", valueDescription = "<mode>", description = "When to destroy runtime. 'legacy' and 'on-shutdown' are currently supported. NOTE: 'legacy' mode is deprecated and will be removed.")
    var destroyRuntimeMode: String? = "on-shutdown"

    @Argument(value="-Xgc", valueDescription = "<gc>", description = "GC to use, 'noop', 'stms' and 'cms' are currently supported. Works only with -memory-model experimental")
    var gc: String? = null

    @Argument(value = "-Xir-property-lazy-initialization", valueDescription = "{disable|enable}", description = "Initialize top level properties lazily per file")
    var propertyLazyInitialization: String? = null

    // TODO: Remove when legacy MM is gone.
    @Argument(
        value = "-Xworker-exception-handling",
        valueDescription = "<mode>",
        description = "Unhandled exception processing in Worker.executeAfter. Possible values: 'legacy', 'use-hook'. The default value is 'legacy', for -memory-model experimental the default value is 'use-hook'"
    )
    var workerExceptionHandling: String? = null

    @Argument(
        value = "-Xllvm-variant",
        valueDescription = "{dev|user|absolute path to llvm}",
        description = "Choose LLVM distribution which will be used during compilation."
    )
    var llvmVariant: String? = null

    @Argument(
        value = "-Xbinary",
        valueDescription = "<option=value>",
        description = "Specify binary option"
    )
    var binaryOptions: Array<String>? = null

    @Argument(value = "-Xruntime-logs", valueDescription = "<tag1=level1,tag2=level2,...>", description = "Enable logging for runtime with tags.")
    var runtimeLogs: String? = null

    @Argument(
        value = "-Xdump-tests-to",
        valueDescription = "<path>",
        description = "Path to a file to dump the list of all available tests"
    )
    var testDumpOutputPath: String? = null

    @Argument(value = "-Xlazy-ir-for-caches", valueDescription = "{disable|enable}", description = "Use lazy IR for cached libraries")
    var lazyIrForCaches: String? = null

    @Argument(value = "-Xpartial-linkage", valueDescription = "{enable|disable}", description = "Use partial linkage mode")
    var partialLinkageMode: String? = null
        set(value) {
            checkFrozen()
            field = if (value.isNullOrEmpty()) null else value
        }

    @Argument(value = "-Xpartial-linkage-loglevel", valueDescription = "{info|warning|error}", description = "Partial linkage compile-time log level")
    var partialLinkageLogLevel: String? = null
        set(value) {
            checkFrozen()
            field = if (value.isNullOrEmpty()) null else value
        }

    @Argument(value = "-Xomit-framework-binary", description = "Omit binary when compiling framework")
    var omitFrameworkBinary: Boolean = false

    @Argument(value = "-Xcompile-from-bitcode", description = "Continue compilation from bitcode file", valueDescription = "<path>")
    var compileFromBitcode: String? = null

    @Argument(
        value = "-Xread-dependencies-from",
        description = "Serialized dependencies to use for linking",
        valueDescription = "<path>"
    )
    var serializedDependencies: String? = null

    @Argument(value = "-Xwrite-dependencies-to", description = "Path for writing backend dependencies")
    var saveDependenciesPath: String? = null

    @Argument(value = "-Xsave-llvm-ir-directory", description = "Directory that should contain results of -Xsave-llvm-ir-after=<phase>")
    var saveLlvmIrDirectory: String? = null

    override fun configureAnalysisFlags(collector: MessageCollector, languageVersion: LanguageVersion): MutableMap<AnalysisFlag<*>, Any> =
        super.configureAnalysisFlags(collector, languageVersion).also {
            val optInList = it[AnalysisFlags.optIn] as List<*>
            it[AnalysisFlags.optIn] = optInList + listOf("kotlin.ExperimentalUnsignedTypes")
            if (printIr)
                phasesToDumpAfter = arrayOf("ALL")
        }

    override fun checkIrSupport(languageVersionSettings: LanguageVersionSettings, collector: MessageCollector) {
        if (languageVersionSettings.languageVersion < LanguageVersion.KOTLIN_1_4
            || languageVersionSettings.apiVersion < ApiVersion.KOTLIN_1_4
        ) {
            collector.report(
                severity = CompilerMessageSeverity.ERROR,
                message = "Native backend cannot be used with language or API version below 1.4"
            )
        }
    }

    companion object {
        const val EMBED_BITCODE_FLAG = "-Xembed-bitcode"
        const val EMBED_BITCODE_MARKER_FLAG = "-Xembed-bitcode-marker"
        const val STATIC_FRAMEWORK_FLAG = "-Xstatic-framework"
        const val INCLUDE_ARG = "-Xinclude"
        const val CACHED_LIBRARY = "-Xcached-library"
        const val ADD_CACHE = "-Xadd-cache"
        const val SHORT_MODULE_NAME_ARG = "-Xshort-module-name"
    }
}
