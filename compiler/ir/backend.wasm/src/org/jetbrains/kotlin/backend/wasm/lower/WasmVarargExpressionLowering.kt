/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm.lower

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irComposite
import org.jetbrains.kotlin.backend.wasm.WasmBackendContext
import org.jetbrains.kotlin.backend.wasm.utils.getWasmArrayAnnotation
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.interpreter.toIrConst
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.util.OperatorNameConventions
import java.lang.IllegalArgumentException

internal class WasmVarargExpressionLowering(
    private val context: WasmBackendContext
) : FileLoweringPass, IrElementTransformerVoidWithContext() {
    val symbols = context.wasmSymbols

    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(this)
    }

    // Helper which wraps an array class and allows to access it's commonly used methods.
    private class ArrayDescr(val arrayType: IrType, val context: WasmBackendContext) {
        val arrayClass =
            arrayType.getClass() ?: throw IllegalArgumentException("Argument ${arrayType.render()} must have a class")

        init {
            check(arrayClass.symbol in context.wasmSymbols.arrays) { "Argument ${ir2string(arrayClass)} must be an array" }
        }

        val isUnsigned
            get() = arrayClass.symbol in context.wasmSymbols.unsignedTypesToUnsignedArrays.values

        val primaryConstructor: IrConstructor
            get() {
                if (isUnsigned)
                    return arrayClass.constructors.find { it.valueParameters.singleOrNull()?.type == context.irBuiltIns.intType }!!
                return arrayClass.primaryConstructor!!
            }
        val constructors
            get() = arrayClass.constructors

        val setMethod
            get() = arrayClass.getSimpleFunction("set")!!.owner
        val getMethod
            get() = arrayClass.getSimpleFunction("get")!!.owner
        val sizeMethod
            get() = arrayClass.getPropertyGetter("size")!!.owner
        val elementType: IrType
            get() {
                if (arrayType.isBoxedArray)
                    return arrayType.getArrayElementType(context.irBuiltIns)
                // getArrayElementType doesn't work on unsigned arrays, use workaround instead
                return getMethod.returnType
            }

        val copyInto: IrSimpleFunction
            get() {
                val func = context.wasmSymbols.arraysCopyInto.find {
                    it.owner.extensionReceiverParameter?.type?.classOrNull?.owner == arrayClass
                }

                return func?.owner ?: throw IllegalArgumentException("copyInto is not found for ${arrayType.render()}")
            }

    }

    private fun IrBlockBuilder.irCreateArray(size: IrExpression, arrDescr: ArrayDescr) =
        irCall(arrDescr.primaryConstructor).apply {
            putValueArgument(0, size)
            if (typeArgumentsCount >= 1) {
                check(typeArgumentsCount == 1 && arrDescr.arrayClass.typeParameters.size == 1)
                putTypeArgument(0, arrDescr.elementType)
            }
            type = arrDescr.arrayType
        }

    // Represents single contiguous sequence of vararg arguments. It can generate IR for various operations on this
    // segments. It's used to handle spreads and normal vararg arguments in a uniform manner.
    private sealed class VarargSegmentBuilder(val wasmContext: WasmBackendContext) {
        // Returns an expression which calculates size of this spread.
        abstract fun IrBlockBuilder.irSize(): IrExpression

        // Adds code into the current block which copies this spread into destArr.
        // If indexVar is present uses it as a start index in the destination array.
        abstract fun IrBlockBuilder.irCopyInto(destArr: IrVariable, indexVar: IrVariable?)

        class Plain(val exprs: List<IrVariable>, wasmContext: WasmBackendContext) :
            VarargSegmentBuilder(wasmContext) {

            override fun IrBlockBuilder.irSize() = irInt(exprs.size)

            override fun IrBlockBuilder.irCopyInto(destArr: IrVariable, indexVar: IrVariable?) {
                val destArrDescr = ArrayDescr(destArr.type, wasmContext)

                // An infinite sequence of natural numbers possibly shifted by the indexVar when it's available
                val indexes = generateSequence(0) { it + 1 }
                    .map { irInt(it) }
                    .let { seq ->
                        if (indexVar != null) seq.map { irIntPlus(irGet(indexVar), it, wasmContext) }
                        else seq
                    }

                for ((element, index) in exprs.asSequence().zip(indexes)) {
                    +irCall(destArrDescr.setMethod).apply {
                        dispatchReceiver = irGet(destArr)
                        putValueArgument(0, index)
                        putValueArgument(1, irGet(element))
                    }
                }
            }
        }

        class Spread(val exprVar: IrVariable, wasmContext: WasmBackendContext) :
            VarargSegmentBuilder(wasmContext) {

            val srcArrDescr = ArrayDescr(exprVar.type, wasmContext) // will check that exprVar is an array

            override fun IrBlockBuilder.irSize(): IrExpression =
                irCall(srcArrDescr.sizeMethod).apply {
                    dispatchReceiver = irGet(exprVar)
                }

            override fun IrBlockBuilder.irCopyInto(destArr: IrVariable, indexVar: IrVariable?) {
                assert(srcArrDescr.arrayClass == destArr.type.getClass()) { "type checker failure?" }

                val destIdx = indexVar?.let { irGet(it) } ?: irInt(0)

                +irCall(srcArrDescr.copyInto).apply {
                    if (typeArgumentsCount >= 1) {
                        check(typeArgumentsCount == 1 && srcArrDescr.arrayClass.typeParameters.size == 1)
                        putTypeArgument(0, srcArrDescr.elementType)
                    }
                    extensionReceiver = irGet(exprVar)  // source
                    putValueArgument(0, irGet(destArr)) // destination
                    putValueArgument(1, destIdx)        // destinationOffset
                    putValueArgument(2, irInt(0))       // startIndex
                    putValueArgument(3, irSize())       // endIndex
                }
            }
        }
    }

    // This is needed to setup proper extension and dispatch receivers for the VarargSegmentBuilder.
    private fun IrBlockBuilder.irCopyInto(destArr: IrVariable, indexVar: IrVariable?, segment: VarargSegmentBuilder) =
        with(segment) {
            this@irCopyInto.irCopyInto(destArr, indexVar)
        }

    private fun IrBlockBuilder.irSize(segment: VarargSegmentBuilder) =
        with(segment) {
            this@irSize.irSize()
        }

    private fun tryGetConstructorOverWasmArray(irVararg: IrVararg): IrConstructor? =
        irVararg.type.getClass()!!.constructors.firstOrNull { ctor ->
            ctor.valueParameters.singleOrNull()?.type?.getClass()?.getWasmArrayAnnotation() != null
        }

    private fun tryVisitWithNoSpreadPrimitives(irVararg: IrVararg): IrExpression? {
        check(irVararg.elements.isNotEmpty())

        val constructorOverWasmArray = tryGetConstructorOverWasmArray(irVararg) ?: return null
        val wasmArrayType = constructorOverWasmArray.valueParameters[0].type

        val kind = (irVararg.elements[0] as? IrConst<*>)?.kind ?: return null
        if (kind == IrConstKind.String || kind == IrConstKind.Null) return null
        if (irVararg.elements.any { it !is IrConst<*> || it.kind != kind }) return null

        val elementConstValues = irVararg.elements.map { (it as IrConst<*>).value!! }

        val resource = when (irVararg.varargElementType) {
            context.irBuiltIns.byteType -> elementConstValues.map { (it as Byte).toLong() }
            context.irBuiltIns.booleanType -> elementConstValues.map { if (it as Boolean) 1L else 0L }
            context.irBuiltIns.intType -> elementConstValues.map { (it as Int).toLong() }
            context.irBuiltIns.shortType -> elementConstValues.map { (it as Short).toLong() }
            context.irBuiltIns.longType -> elementConstValues.map { it as Long }
            else -> return null
        }

        val resourceId = context.registerResource(resource)

        val builder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol)

        val wasmArray = builder.irCall(context.wasmSymbols.wasmLoadResource).also {
            it.putTypeArgument(0, wasmArrayType)
            it.putTypeArgument(1, irVararg.varargElementType)
            it.putValueArgument(0, resourceId.toIrConst(context.irBuiltIns.intType))
        }

        return builder.irCall(constructorOverWasmArray).also {
            it.putValueArgument(0, wasmArray)
        }
    }

    private fun tryVisitWithNoSpread(irVararg: IrVararg): IrExpression? {
        if (irVararg.elements.any { it is IrSpreadElement }) return null
        val constructorOverWasmArray = tryGetConstructorOverWasmArray(irVararg) ?: return null
        val builder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol)
        val wasmArrayType = constructorOverWasmArray.valueParameters[0].type
        val stackPutFunction = context.wasmSymbols.stackPutPrimitives[irVararg.varargElementType] ?: context.wasmSymbols.stackPutAny
        val wasmArrayExpression = builder.irComposite(resultType = wasmArrayType) {
            irVararg.elements.forEach { element ->
                +irCall(stackPutFunction).also {
                    it.putValueArgument(0, element as IrExpression)
                }
            }
            +irCall(this@WasmVarargExpressionLowering.context.wasmSymbols.arrayNewFixed, wasmArrayType).also { call ->
                call.putTypeArgument(0, wasmArrayType)
                call.putValueArgument(0, irVararg.elements.size.toIrConst(context.irBuiltIns.intType))
            }
        }

        return builder.irCall(constructorOverWasmArray).also {
            it.putValueArgument(0, wasmArrayExpression)
        }
    }

    override fun visitVararg(expression: IrVararg): IrExpression {
        // Optimization in case if we have a single spread element
        val singleSpreadElement = expression.elements.singleOrNull() as? IrSpreadElement
        if (singleSpreadElement != null) {
            val spreadExpr = singleSpreadElement.expression
            if (isImmediatelyCreatedArray(spreadExpr))
                return spreadExpr.transform(this, null)
        }

        // Lower nested varargs
        val irVararg = super.visitVararg(expression) as IrVararg

        if (irVararg.elements.none { it is IrSpreadElement }) {
            if (irVararg.elements.isNotEmpty()) {
                tryVisitWithNoSpreadPrimitives(irVararg)?.let { return it }
            }
            tryVisitWithNoSpread(irVararg)?.let { return it }
        }

        val builder = context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol)
        // Create temporary variable for each element and emit them all at once to preserve
        // argument evaluation order as per kotlin language spec.
        val elementVars = irVararg.elements
            .map {
                val exp = if (it is IrSpreadElement) it.expression else (it as IrExpression)
                currentScope!!.scope.createTemporaryVariable(exp, "vararg_temp")
            }

        val segments: List<VarargSegmentBuilder> = sequence {
            val currentElements = mutableListOf<IrVariable>()

            for ((el, tempVar) in irVararg.elements.zip(elementVars)) {
                when (el) {
                    is IrExpression -> currentElements.add(tempVar)
                    is IrSpreadElement -> {
                        if (currentElements.isNotEmpty()) {
                            yield(VarargSegmentBuilder.Plain(currentElements.toList(), context))
                            currentElements.clear()
                        }
                        yield(VarargSegmentBuilder.Spread(tempVar, context))
                    }
                }
            }
            if (currentElements.isNotEmpty())
                yield(VarargSegmentBuilder.Plain(currentElements.toList(), context))
        }.toList()

        val destArrayDescr = ArrayDescr(irVararg.type, context)
        return builder.irComposite(irVararg) {
            // Emit all of the variables first so that all vararg expressions
            // are evaluated only once and in order of their appearance.
            elementVars.forEach { +it }

            val arrayLength = segments
                .map { irSize(it) }
                .reduceOrNull { acc, exp -> irIntPlus(acc, exp) }
                ?: irInt(0)
            val arrayTempVariable = irTemporary(
                value = irCreateArray(arrayLength, destArrayDescr),
                nameHint = "vararg_array")
            val indexVar = if (segments.size >= 2) irTemporary(irInt(0), "vararg_idx") else null

            segments.forEach {
                irCopyInto(arrayTempVariable, indexVar, it)

                if (indexVar != null)
                    +irSet(indexVar, irIntPlus(irGet(indexVar), irSize(it)))
            }

            +irGet(arrayTempVariable)
        }
    }

    override fun visitFunctionAccess(expression: IrFunctionAccessExpression) =
        transformFunctionAccessExpression(expression)

    private fun transformFunctionAccessExpression(expression: IrFunctionAccessExpression): IrExpression {
        expression.transformChildrenVoid()
        val builder by lazy { context.createIrBuilder(currentScope!!.scope.scopeOwnerSymbol) }

        // Replace empty vararg arguments with empty array construction
        for (argumentIdx in 0 until expression.valueArgumentsCount) {
            val argument = expression.getValueArgument(argumentIdx)
            val parameter = expression.symbol.owner.valueParameters[argumentIdx]
            val varargElementType = parameter.varargElementType
            if (argument == null && varargElementType != null) {
                val arrayClass = parameter.type.classOrNull!!.owner
                val primaryConstructor = arrayClass.primaryConstructor!!
                val emptyArrayCall = with(builder) {
                    irCall(primaryConstructor).apply {
                        putValueArgument(0, irInt(0))
                        if (primaryConstructor.typeParameters.isNotEmpty()) {
                            check(primaryConstructor.typeParameters.size == 1)
                            putTypeArgument(0, parameter.varargElementType)
                        }
                    }
                }
                expression.putValueArgument(argumentIdx, emptyArrayCall)
            }
        }
        return expression
    }

    private fun IrBlockBuilder.irIntPlus(rhs: IrExpression, lhs: IrExpression): IrExpression =
        irIntPlus(rhs, lhs, this@WasmVarargExpressionLowering.context)

    private fun isImmediatelyCreatedArray(expr: IrExpression): Boolean =
        when (expr) {
            is IrFunctionAccessExpression -> {
                val arrDescr = ArrayDescr(expr.type, context)
                expr.symbol.owner in arrDescr.constructors || expr.symbol == context.wasmSymbols.arrayOfNulls
            }
            is IrTypeOperatorCall -> isImmediatelyCreatedArray(expr.argument)
            is IrComposite ->
                expr.statements.size == 1 &&
                        expr.statements[0] is IrExpression &&
                        isImmediatelyCreatedArray(expr.statements[0] as IrExpression)
            is IrVararg -> true // Vararg always produces a fresh array
            else -> false
        }
}

private fun IrBlockBuilder.irIntPlus(rhs: IrExpression, lhs: IrExpression, wasmContext: WasmBackendContext): IrExpression {
    val plusOp = wasmContext.wasmSymbols.getBinaryOperator(
        OperatorNameConventions.PLUS, context.irBuiltIns.intType, context.irBuiltIns.intType
    ).owner

    return irCall(plusOp).apply {
        dispatchReceiver = rhs
        putValueArgument(0, lhs)
    }
}
