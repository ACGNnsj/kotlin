/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.checkers

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationUseSiteTarget.*
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.calls.util.getType
import org.jetbrains.kotlin.resolve.needsMfvcFlattening
import org.jetbrains.kotlin.types.KotlinType

object MultiFieldValueClassAnnotationsChecker : DeclarationChecker {
    private fun report(context: DeclarationCheckerContext, name: String, type: KotlinType, annotation: KtAnnotation) {
        if (!type.needsMfvcFlattening()) return
        for (entry in annotation.entries) {
            context.trace.report(Errors.ANNOTATION_ON_ILLEGAL_MULTI_FIELD_VALUE_CLASS_TYPED_TARGET.on(entry, name))
        }
    }

    override fun check(declaration: KtDeclaration, descriptor: DeclarationDescriptor, context: DeclarationCheckerContext) {

        for (annotationDecl in declaration.annotations) {
            val (hint, type) = when (annotationDecl.useSiteTarget?.getAnnotationUseSiteTarget()) {
                FIELD -> "fields" to (descriptor as? PropertyDescriptor ?: continue).type
                PROPERTY_DELEGATE_FIELD -> "delegate fields" to ((declaration as? KtProperty)?.takeIf { descriptor is PropertyDescriptor }?.delegateExpression?.getType(context.trace.bindingContext) ?: continue)
                FILE, PROPERTY, PROPERTY_SETTER -> continue
                PROPERTY_GETTER -> "getters" to (descriptor as? PropertyDescriptor ?: continue).type
                RECEIVER -> "receivers" to ((descriptor as? CallableMemberDescriptor)?.extensionReceiverParameter?.type ?: continue)
                CONSTRUCTOR_PARAMETER, SETTER_PARAMETER -> "parameters" to (descriptor as? ValueParameterDescriptor ?: continue).type
                null -> when {
                    descriptor is PropertyDescriptor -> {
                        if (declaration !is KtProperty) continue
                        val receiverDescriptor = descriptor.extensionReceiverParameter ?: continue
                        val receiverDeclaration = declaration.receiverTypeReference ?: continue
                        for (receiverAnnotation in receiverDeclaration.annotations) {
                            report(context, "receivers", receiverDescriptor.type, receiverAnnotation)
                        }
                        continue
                    }

                    descriptor is FieldDescriptor -> "fields" to descriptor.correspondingProperty.type
                    descriptor is ValueParameterDescriptor -> "parameters" to descriptor.type
                    descriptor is VariableDescriptor -> "variables" to descriptor.type
                    descriptor is PropertyGetterDescriptor &&
                            descriptor.extensionReceiverParameter == null &&
                            descriptor.contextReceiverParameters.isEmpty() ->
                        "getters" to descriptor.correspondingProperty.type

                    else -> continue
                }
            }
            report(context, hint, type, annotationDecl)
        }

        if (descriptor is CallableDescriptor && declaration is KtDeclarationWithBody) {
            descriptor.valueParameters.zip(declaration.valueParameters) { desc, decl -> check(decl, desc, context) }
            if (descriptor.extensionReceiverParameter != null && declaration is KtCallableDeclaration && declaration.receiverTypeReference != null) {
                val receiverType = descriptor.extensionReceiverParameter!!.type
                for (annotation in declaration.receiverTypeReference!!.annotations) {
                    report(context, "contexts", receiverType, annotation)
                }
            }
        }
    }
}
