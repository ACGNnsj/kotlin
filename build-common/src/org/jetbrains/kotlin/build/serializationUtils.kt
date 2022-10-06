/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.build

import org.jetbrains.kotlin.cli.common.arguments.collectProperties
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor

inline fun <reified T : Any> serializeToPlainText(instance: T): String = serializeToPlainText(instance, T::class)

fun <T : Any> serializeToPlainText(instance: T, klass: KClass<T>): String {
    val lines = ArrayList<String>()
    for (property in klass.memberProperties) {
        val value = property.get(instance)
        if (value != null) {
            lines.add("${property.name}=$value")
        }
    }
    return lines.joinToString("\n")
}

inline fun <reified T : Any> deserializeFromPlainText(str: String): T? = deserializeFromPlainText(str, T::class)

fun <T : Any> deserializeFromPlainText(str: String, klass: KClass<T>): T? {
    val args = ArrayList<Any?>()
    val properties = str
            .split("\n")
            .filter(String::isNotBlank)
            .associate { it.substringBefore("=") to it.substringAfter("=") }

    val primaryConstructor = klass.primaryConstructor
                             ?: throw IllegalStateException("${klass.java} does not have primary constructor")
    for (param in primaryConstructor.parameters.sortedBy { it.index }) {
        val argumentString = properties[param.name]

        if (argumentString == null) {
            if (param.type.isMarkedNullable) {
                args.add(null)
                continue
            }
            else {
                return null
            }
        }

        val argument: Any? = when (param.type.classifier) {
            Int::class -> argumentString.toInt()
            Boolean::class -> argumentString.toBoolean()
            String::class -> argumentString
            else -> throw IllegalStateException("Unexpected property type: ${param.type}")
        }

        args.add(argument)
    }

    return primaryConstructor.call(*args.toTypedArray())
}

@Suppress("UNCHECKED_CAST")
fun <T : Any> serializeArgs(args: T) =
    collectProperties(args::class as KClass<T>, false)
        .filter { property -> property.name !in filteredProperties }
        .associateBy(
            keySelector = { property -> property.name },
            valueTransform = { property -> property.get(args).toString() })

// TODO: aocherepanov: look throw
val filteredProperties = listOf(
    "asd"
)

fun <T : Any> serializeArgsToString(args: T) = serializeMapToString(serializeArgs(args))
fun serializeMapToString(myList: Map<String, String>) = myList.map { "${it.key}=${it.value}" }.joinToString("\n")

fun deserializeMapFromString(inputString: String) = inputString
    .split("\n")
    .filter(String::isNotBlank)
    .associate { it.substringBefore("=") to it.substringAfter("=") }


// TODO: aocherepanov: smart comparator?
fun <T : Map<String, String>> T.compare(two: T): Boolean {
    return this == two
}