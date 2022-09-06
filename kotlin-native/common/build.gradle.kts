/*
 * Copyright 2010-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

plugins {
    id("compile-to-bitcode")
}

bitcode {
    allTargets {
        module("files") {
            headersDirs.from(layout.projectDirectory.dir("src/files/headers"))
        }
        module("env") {
            headersDirs.from(layout.projectDirectory.dir("src/env/headers"))
        }
    }
}

val hostName: String by project

val build by tasks.getting {
    dependsOn("${hostName}Common")
}

val clean by tasks.getting {
    doFirst {
        delete(buildDir)
    }
}
