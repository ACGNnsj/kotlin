import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    implementation(kotlinStdlib())
    @Suppress("UNCHECKED_CAST")
    rootProject.extra["kotlinJpsPluginEmbeddedDependencies"]
        .let { it as List<String> }
        .forEach { implementation(project(it)) }

    @Suppress("UNCHECKED_CAST")
    rootProject.extra["kotlinJpsPluginMavenDependencies"]
        .let { it as List<String> }
        .forEach { implementation(project(it)) }

    @Suppress("UNCHECKED_CAST")
    rootProject.extra["kotlinJpsPluginMavenDependenciesNonTransitiveLibs"]
        .let { it as List<String> }
        .forEach { implementation(it) { isTransitive = false } }

    compileOnly(intellijUtilRt())
    compileOnly(intellijPlatformUtil())
    compileOnly(jpsModel())
    compileOnly(jpsModelImpl())
    compileOnly(jpsModelSerialization())

    testImplementation(project(":compiler:cli-common"))
    testImplementation(jpsModelSerialization())
    testImplementation(commonDependency("junit:junit"))
    testImplementation(kotlin("test-junit"))
}

sourceSets {
    "main" { projectDefault() }
    "test" { projectDefault() }
}

runtimeJar()

tasks.withType<KotlinCompilationTask<*>>().configureEach {
    compilerOptions.apiVersion.value(KotlinVersion.KOTLIN_1_8).finalizeValueOnRead()
    compilerOptions.languageVersion.value(KotlinVersion.KOTLIN_1_8).finalizeValueOnRead()
}