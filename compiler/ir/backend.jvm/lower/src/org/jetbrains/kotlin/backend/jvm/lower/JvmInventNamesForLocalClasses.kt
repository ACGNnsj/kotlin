/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.lower.InventNamesForLocalClasses
import org.jetbrains.kotlin.backend.common.phaser.makeIrModulePhase
import org.jetbrains.kotlin.backend.jvm.JvmBackendContext
import org.jetbrains.kotlin.codegen.JvmCodegenUtil
import org.jetbrains.kotlin.ir.declarations.IrAttributeContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.resolve.jvm.JvmClassName
import org.jetbrains.org.objectweb.asm.Type

val inventNamesForLocalClassesPhase = makeIrModulePhase<JvmBackendContext>(
//val inventNamesForLocalClassesPhase = makeIrFilePhase<JvmBackendContext>(
    { context -> JvmInventNamesForLocalClasses(context) },
    name = "InventNamesForLocalClasses",
    description = "Invent names for local classes and anonymous objects",
    // MainMethodGeneration introduces lambdas, needing names for their local classes.
    prerequisite = setOf(mainMethodGenerationPhase)
)

val inventNamesForLocalClassesPhase2 = makeIrModulePhase<JvmBackendContext>(
//val inventNamesForLocalClassesPhase = makeIrFilePhase<JvmBackendContext>(
    { context -> JvmInventNamesForLocalClasses(context) },
    name = "InventNamesForLocalClasses2",
    description = "Invent names for local classes and anonymous objects",
    // MainMethodGeneration introduces lambdas, needing names for their local classes.
    prerequisite = setOf(mainMethodGenerationPhase)
)

val inventNamesForNewLocalClassesPhase = makeIrModulePhase<JvmBackendContext>(
    { context -> JvmInventNamesForNewLocalClasses(context) },
    name = "InventNamesForLocalClasses3",
    description = "Invent names for local classes and anonymous objects",
    // MainMethodGeneration introduces lambdas, needing names for their local classes.
    prerequisite = setOf(mainMethodGenerationPhase)
)

open class JvmInventNamesForLocalClasses(
    protected val context: JvmBackendContext
) : InventNamesForLocalClasses(allowTopLevelCallables = true) {
    override fun computeTopLevelClassName(clazz: IrClass): String {
        val file = clazz.parent as? IrFile
            ?: throw AssertionError("Top-level class expected: ${clazz.render()}")
        val classFqn =
            if (clazz.origin == IrDeclarationOrigin.FILE_CLASS ||
                clazz.origin == IrDeclarationOrigin.SYNTHETIC_FILE_CLASS
            ) {
                file.getFileClassInfo().fileClassFqName
            } else {
                file.fqName.child(clazz.name)
            }
        return JvmClassName.byFqNameWithoutInnerClasses(classFqn).internalName
    }

    override fun sanitizeNameIfNeeded(name: String): String {
        return JvmCodegenUtil.sanitizeNameIfNeeded(name, context.state.languageVersionSettings)
            .replace("<", "\$_").replace(">", "\$_")
    }

    override fun putLocalClassName(declaration: IrAttributeContainer, localClassName: String) {
        context.putLocalClassType(declaration, Type.getObjectType(localClassName))
    }
}

// TODO try to use only one "InventNames"
class JvmInventNamesForNewLocalClasses(context: JvmBackendContext) : JvmInventNamesForLocalClasses(context) {
    private val namesToIndex = mutableMapOf<String, Int>()
    override fun putLocalClassName(declaration: IrAttributeContainer, localClassName: String) {
        if (context.getLocalClassType(declaration) != null) return
        val inlinedName = localClassName + "\$\$inlined"
        val index = namesToIndex[inlinedName]
        namesToIndex[inlinedName] = (index ?: -1) + 1

        val safeName = if (index == null) inlinedName else inlinedName + index
        context.putLocalClassType(declaration, Type.getObjectType(safeName))
    }
}
