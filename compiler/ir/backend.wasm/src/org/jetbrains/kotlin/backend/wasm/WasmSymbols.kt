/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.wasm

import org.jetbrains.kotlin.backend.common.ir.Symbols
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.builtins.isFunctionType
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.PackageViewDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.backend.js.ReflectionSymbols
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.SymbolTable
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.render
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.scopes.MemberScope

@OptIn(ObsoleteDescriptorBasedAPI::class)
class WasmSymbols(
    private val context: WasmBackendContext,
    private val symbolTable: SymbolTable
) : Symbols(context.irBuiltIns, symbolTable) {

    private val kotlinTopLevelPackage: PackageViewDescriptor =
        context.module.getPackage(FqName("kotlin"))
    private val enumsInternalPackage: PackageViewDescriptor =
        context.module.getPackage(FqName("kotlin.enums"))
    private val wasmInternalPackage: PackageViewDescriptor =
        context.module.getPackage(FqName("kotlin.wasm.internal"))
    private val kotlinJsPackage: PackageViewDescriptor =
        context.module.getPackage(FqName("kotlin.js"))
    private val collectionsPackage: PackageViewDescriptor =
        context.module.getPackage(StandardNames.COLLECTIONS_PACKAGE_FQ_NAME)
    private val builtInsPackage: PackageViewDescriptor =
        context.module.getPackage(StandardNames.BUILT_INS_PACKAGE_FQ_NAME)
    private val kotlinTestPackage: PackageViewDescriptor =
        context.module.getPackage(FqName("kotlin.test"))

    internal inner class WasmReflectionSymbols : ReflectionSymbols {
        override val createKType: IrSimpleFunctionSymbol = getInternalFunction("createKType")
        override val getClassData: IrSimpleFunctionSymbol = getInternalFunction("wasmGetTypeInfoData")
        override val getKClass: IrSimpleFunctionSymbol = getInternalFunction("getKClass")
        override val getKClassFromExpression: IrSimpleFunctionSymbol = getInternalFunction("getKClassFromExpression")
        override val createDynamicKType: IrSimpleFunctionSymbol get() = error("Dynamic type is not supported by WASM")
        override val createKTypeParameter: IrSimpleFunctionSymbol = getInternalFunction("createKTypeParameter")
        override val getStarKTypeProjection = getInternalFunction("getStarKTypeProjection")
        override val createCovariantKTypeProjection = getInternalFunction("createCovariantKTypeProjection")
        override val createInvariantKTypeProjection = getInternalFunction("createInvariantKTypeProjection")
        override val createContravariantKTypeProjection = getInternalFunction("createContravariantKTypeProjection")

        override val primitiveClassesObject = getInternalClass("PrimitiveClasses")
        override val kTypeClass: IrClassSymbol = getIrClass(FqName("kotlin.reflect.KClass"))

        val wasmTypeInfoData: IrClassSymbol = getInternalClass("TypeInfoData")
    }

    internal val reflectionSymbols: WasmReflectionSymbols = WasmReflectionSymbols()

    internal val eagerInitialization: IrClassSymbol = getIrClass(FqName("kotlin.EagerInitialization"))

    internal val isNotFirstWasmExportCall: IrPropertySymbol = symbolTable.referenceProperty(
        getProperty(FqName.fromSegments(listOf("kotlin", "wasm", "internal", "isNotFirstWasmExportCall")))
    )
    internal val throwAsJsException: IrSimpleFunctionSymbol = getInternalFunction("throwAsJsException")

    override val throwNullPointerException = getInternalFunction("THROW_NPE")
    override val throwISE = getInternalFunction("THROW_ISE")
    override val throwTypeCastException = getInternalFunction("THROW_CCE")
    val throwIAE = getInternalFunction("THROW_IAE")
    val throwNoBranchMatchedException =
        getInternalFunction("throwNoBranchMatchedException")
    override val throwUninitializedPropertyAccessException =
        getInternalFunction("throwUninitializedPropertyAccessException")
    override val defaultConstructorMarker =
        getIrClass(FqName("kotlin.wasm.internal.DefaultConstructorMarker"))
    override val throwKotlinNothingValueException: IrSimpleFunctionSymbol
        get() = TODO()
    override val stringBuilder =
        getIrClass(FqName("kotlin.text.StringBuilder"))
    override val coroutineImpl =
        context.coroutineSymbols.coroutineImpl
    override val coroutineSuspendedGetter =
        context.coroutineSymbols.coroutineSuspendedGetter
    override val getContinuation =
        getInternalFunction("getContinuation")
    override val continuationClass =
        context.coroutineSymbols.continuationClass
    override val coroutineContextGetter =
        symbolTable.referenceSimpleFunction(context.coroutineSymbols.coroutineContextProperty.getter!!)
    override val suspendCoroutineUninterceptedOrReturn =
        getInternalFunction("suspendCoroutineUninterceptedOrReturn")
    override val coroutineGetContext =
        getInternalFunction("getCoroutineContext")
    override val returnIfSuspended =
        getInternalFunction("returnIfSuspended")

    val enumEntries = getIrClass(FqName.fromSegments(listOf("kotlin", "enums", "EnumEntries")))
    val createEnumEntries = findFunctions(enumsInternalPackage.memberScope, Name.identifier("enumEntries"))
        .find { it.valueParameters.firstOrNull()?.type?.isFunctionType == false }
        .let { symbolTable.referenceSimpleFunction(it!!) }

    val enumValueOfIntrinsic = getInternalFunction("enumValueOfIntrinsic")
    val enumValuesIntrinsic = getInternalFunction("enumValuesIntrinsic")

    val coroutineEmptyContinuation: IrPropertySymbol = symbolTable.referenceProperty(
        getProperty(FqName.fromSegments(listOf("kotlin", "wasm", "internal", "EmptyContinuation")))
    )

    override val functionAdapter: IrClassSymbol
        get() = TODO()

    val wasmUnreachable = getInternalFunction("wasm_unreachable")

    val voidClass = getIrClass(FqName("kotlin.wasm.internal.Void"))
    val voidType by lazy { voidClass.defaultType }

    private val consumeAnyIntoVoid = getInternalFunction("consumeAnyIntoVoid")
    private val consumePrimitiveIntoVoid = mapOf(
        context.irBuiltIns.booleanType to getInternalFunction("consumeBooleanIntoVoid"),
        context.irBuiltIns.byteType to getInternalFunction("consumeByteIntoVoid"),
        context.irBuiltIns.shortType to getInternalFunction("consumeShortIntoVoid"),
        context.irBuiltIns.charType to getInternalFunction("consumeCharIntoVoid"),
        context.irBuiltIns.intType to getInternalFunction("consumeIntIntoVoid"),
        context.irBuiltIns.longType to getInternalFunction("consumeLongIntoVoid"),
        context.irBuiltIns.floatType to getInternalFunction("consumeFloatIntoVoid"),
        context.irBuiltIns.doubleType to getInternalFunction("consumeDoubleIntoVoid")
    )
    
    fun findVoidConsumer(type: IrType): IrSimpleFunctionSymbol =
        consumePrimitiveIntoVoid[type] ?: consumeAnyIntoVoid

    val equalityFunctions = mapOf(
        context.irBuiltIns.booleanType to getInternalFunction("wasm_i32_eq"),
        context.irBuiltIns.byteType to getInternalFunction("wasm_i32_eq"),
        context.irBuiltIns.shortType to getInternalFunction("wasm_i32_eq"),
        context.irBuiltIns.charType to getInternalFunction("wasm_i32_eq"),
        context.irBuiltIns.intType to getInternalFunction("wasm_i32_eq"),
        context.irBuiltIns.longType to getInternalFunction("wasm_i64_eq")
    )

    val floatEqualityFunctions = mapOf(
        context.irBuiltIns.floatType to getInternalFunction("wasm_f32_eq"),
        context.irBuiltIns.doubleType to getInternalFunction("wasm_f64_eq")
    )

    private fun wasmPrimitiveTypeName(classifier: IrClassifierSymbol): String = with(context.irBuiltIns) {
        when (classifier) {
            booleanClass, byteClass, shortClass, charClass, intClass -> "i32"
            floatClass -> "f32"
            doubleClass -> "f64"
            longClass -> "i64"
            else -> error("Unknown primitive type")
        }
    }

    val comparisonBuiltInsToWasmIntrinsics = context.irBuiltIns.run {
        listOf(
            lessFunByOperandType to "lt",
            lessOrEqualFunByOperandType to "le",
            greaterOrEqualFunByOperandType to "ge",
            greaterFunByOperandType to "gt"
        ).map { (typeToBuiltIn, wasmOp) ->
            typeToBuiltIn.map { (type, builtin) ->
                val wasmType = wasmPrimitiveTypeName(type)
                val markSign = if (wasmType == "i32" || wasmType == "i64") "_s" else ""
                builtin to getInternalFunction("wasm_${wasmType}_$wasmOp$markSign")
            }
        }.flatten().toMap()
    }

    val booleanAnd = getInternalFunction("wasm_i32_and")
    val refEq = getInternalFunction("wasm_ref_eq")
    val refIsNull = getInternalFunction("wasm_ref_is_null")
    val externRefIsNull = getInternalFunction("wasm_externref_is_null")
    val refTest = getInternalFunction("wasm_ref_test_deprecated")
    val refCastNull = getInternalFunction("wasm_ref_cast_deprecated")
    val wasmArrayCopy = getInternalFunction("wasm_array_copy")
    val wasmArrayNewData0 = getInternalFunction("array_new_data0")

    val intToLong = getInternalFunction("wasm_i64_extend_i32_s")

    val rangeCheck = getInternalFunction("rangeCheck")
    val assertFuncs = findFunctions(kotlinTopLevelPackage.memberScope, Name.identifier("assert")).map { symbolTable.referenceSimpleFunction(it) }

    val boxIntrinsic: IrSimpleFunctionSymbol = getInternalFunction("boxIntrinsic")
    val unboxIntrinsic: IrSimpleFunctionSymbol = getInternalFunction("unboxIntrinsic")

    val stringGetLiteral = getFunction("stringLiteral", builtInsPackage)
    val stringGetPoolSize = getInternalFunction("stringGetPoolSize")

    val testFun = maybeGetFunction("test", kotlinTestPackage)
    val suiteFun = maybeGetFunction("suite", kotlinTestPackage)
    val startUnitTests = maybeGetFunction("startUnitTests", kotlinTestPackage)

    val wasmClassId = getInternalFunction("wasmClassId")
    val wasmInterfaceId = getInternalFunction("wasmInterfaceId")

    val wasmIsInterface = getInternalFunction("wasmIsInterface")

    val nullableEquals = getInternalFunction("nullableEquals")
    val anyNtoString = getInternalFunction("anyNtoString")

    val nullableFloatIeee754Equals = getInternalFunction("nullableFloatIeee754Equals")
    val nullableDoubleIeee754Equals = getInternalFunction("nullableDoubleIeee754Equals")

    val unsafeGetScratchRawMemory = getInternalFunction("unsafeGetScratchRawMemory")
    val returnArgumentIfItIsKotlinAny = getInternalFunction("returnArgumentIfItIsKotlinAny")

    val newJsArray = getInternalFunction("newJsArray")
    val jsArrayPush = getInternalFunction("jsArrayPush")

    val startCoroutineUninterceptedOrReturnIntrinsics =
        (0..2).map { getInternalFunction("startCoroutineUninterceptedOrReturnIntrinsic$it") }

    // KProperty implementations
    val kLocalDelegatedPropertyImpl: IrClassSymbol = this.getInternalClass("KLocalDelegatedPropertyImpl")
    val kLocalDelegatedMutablePropertyImpl: IrClassSymbol = this.getInternalClass("KLocalDelegatedMutablePropertyImpl")
    val kProperty0Impl: IrClassSymbol = this.getInternalClass("KProperty0Impl")
    val kProperty1Impl: IrClassSymbol = this.getInternalClass("KProperty1Impl")
    val kProperty2Impl: IrClassSymbol = this.getInternalClass("KProperty2Impl")
    val kMutableProperty0Impl: IrClassSymbol = this.getInternalClass("KMutableProperty0Impl")
    val kMutableProperty1Impl: IrClassSymbol = this.getInternalClass("KMutableProperty1Impl")
    val kMutableProperty2Impl: IrClassSymbol = this.getInternalClass("KMutableProperty2Impl")
    val kMutableProperty0: IrClassSymbol = getIrClass(FqName("kotlin.reflect.KMutableProperty0"))
    val kMutableProperty1: IrClassSymbol = getIrClass(FqName("kotlin.reflect.KMutableProperty1"))
    val kMutableProperty2: IrClassSymbol = getIrClass(FqName("kotlin.reflect.KMutableProperty2"))

    val kTypeStub = getInternalFunction("kTypeStub")

    val arraysCopyInto = findFunctions(collectionsPackage.memberScope, Name.identifier("copyInto"))
        .map { symbolTable.referenceSimpleFunction(it) }

    private val contentToString: List<IrSimpleFunctionSymbol> =
        findFunctions(collectionsPackage.memberScope, Name.identifier("contentToString"))
            .map { symbolTable.referenceSimpleFunction(it) }

    private val contentHashCode: List<IrSimpleFunctionSymbol> =
        findFunctions(collectionsPackage.memberScope, Name.identifier("contentHashCode"))
            .map { symbolTable.referenceSimpleFunction(it) }

    private fun findOverloadForReceiver(arrayType: IrType, overloadsList: List<IrSimpleFunctionSymbol>): IrSimpleFunctionSymbol =
        overloadsList.first {
            val receiverType = it.owner.extensionReceiverParameter?.type
            receiverType != null && arrayType.isNullable() == receiverType.isNullable() && arrayType.classOrNull == receiverType.classOrNull
        }

    fun findContentToStringOverload(arrayType: IrType): IrSimpleFunctionSymbol = findOverloadForReceiver(arrayType, contentToString)

    fun findContentHashCodeOverload(arrayType: IrType): IrSimpleFunctionSymbol = findOverloadForReceiver(arrayType, contentHashCode)

    private val getProgressionLastElementSymbols =
        irBuiltIns.findFunctions(Name.identifier("getProgressionLastElement"), "kotlin", "internal")

    override val getProgressionLastElementByReturnType: Map<IrClassifierSymbol, IrSimpleFunctionSymbol> by lazy {
        getProgressionLastElementSymbols.associateBy { it.owner.returnType.classifierOrFail }
    }

    private val toUIntSymbols = irBuiltIns.findFunctions(Name.identifier("toUInt"), "kotlin")

    override val toUIntByExtensionReceiver: Map<IrClassifierSymbol, IrSimpleFunctionSymbol> by lazy {
        toUIntSymbols.associateBy {
            it.owner.extensionReceiverParameter?.type?.classifierOrFail
                ?: error("Expected extension receiver for ${it.owner.render()}")
        }
    }

    private val toULongSymbols = irBuiltIns.findFunctions(Name.identifier("toULong"), "kotlin")

    override val toULongByExtensionReceiver: Map<IrClassifierSymbol, IrSimpleFunctionSymbol> by lazy {
        toULongSymbols.associateBy {
            it.owner.extensionReceiverParameter?.type?.classifierOrFail
                ?: error("Expected extension receiver for ${it.owner.render()}")
        }
    }

    private val wasmDataRefClass = getIrClass(FqName("kotlin.wasm.internal.reftypes.dataref"))
    val wasmDataRefType by lazy { wasmDataRefClass.defaultType }

    val wasmAnyRefClass = getIrClass(FqName("kotlin.wasm.internal.reftypes.anyref"))

    private val jsAnyClass = getIrClass(FqName("kotlin.js.JsAny"))
    val jsAnyType by lazy { jsAnyClass.defaultType }

    inner class JsInteropAdapters {
        val kotlinToJsStringAdapter = getInternalFunction("kotlinToJsStringAdapter")
        val kotlinToJsAnyAdapter = getInternalFunction("kotlinToJsAnyAdapter")
        val numberToDoubleAdapter = getInternalFunction("numberToDoubleAdapter")

        val jsCheckIsNullOrUndefinedAdapter = getInternalFunction("jsCheckIsNullOrUndefinedAdapter")

        val jsToKotlinStringAdapter = getInternalFunction("jsToKotlinStringAdapter")
        val jsToKotlinAnyAdapter = getInternalFunction("jsToKotlinAnyAdapter")

        val jsToKotlinByteAdapter = getInternalFunction("jsToKotlinByteAdapter")
        val jsToKotlinShortAdapter = getInternalFunction("jsToKotlinShortAdapter")
        val jsToKotlinCharAdapter = getInternalFunction("jsToKotlinCharAdapter")

        val externRefToKotlinIntAdapter = getInternalFunction("externRefToKotlinIntAdapter")
        val externRefToKotlinBooleanAdapter = getInternalFunction("externRefToKotlinBooleanAdapter")
        val externRefToKotlinLongAdapter = getInternalFunction("externRefToKotlinLongAdapter")
        val externRefToKotlinFloatAdapter = getInternalFunction("externRefToKotlinFloatAdapter")
        val externRefToKotlinDoubleAdapter = getInternalFunction("externRefToKotlinDoubleAdapter")

        val kotlinIntToExternRefAdapter = getInternalFunction("kotlinIntToExternRefAdapter")
        val kotlinBooleanToExternRefAdapter = getInternalFunction("kotlinBooleanToExternRefAdapter")
        val kotlinLongToExternRefAdapter = getInternalFunction("kotlinLongToExternRefAdapter")
        val kotlinFloatToExternRefAdapter = getInternalFunction("kotlinFloatToExternRefAdapter")
        val kotlinDoubleToExternRefAdapter = getInternalFunction("kotlinDoubleToExternRefAdapter")
        val kotlinByteToExternRefAdapter = getInternalFunction("kotlinByteToExternRefAdapter")
        val kotlinShortToExternRefAdapter = getInternalFunction("kotlinShortToExternRefAdapter")
        val kotlinCharToExternRefAdapter = getInternalFunction("kotlinCharToExternRefAdapter")
    }

    val jsInteropAdapters = JsInteropAdapters()

    private val jsExportClass = getIrClass(FqName("kotlin.js.JsExport"))
    val jsExportConstructor by lazy { jsExportClass.constructors.single() }

    private val jsNameClass = getIrClass(FqName("kotlin.js.JsName"))
    val jsNameConstructor by lazy { jsNameClass.constructors.single() }

    private val jsFunClass = getIrClass(FqName("kotlin.JsFun"))
    val jsFunConstructor by lazy { jsFunClass.constructors.single() }

    val jsCode = getFunction("js", kotlinJsPackage)

    private fun findClass(memberScope: MemberScope, name: Name): ClassDescriptor =
        memberScope.getContributedClassifier(name, NoLookupLocation.FROM_BACKEND) as ClassDescriptor

    private fun findFunctions(memberScope: MemberScope, name: Name): List<SimpleFunctionDescriptor> =
        memberScope.getContributedFunctions(name, NoLookupLocation.FROM_BACKEND).toList()

    private fun findProperty(memberScope: MemberScope, name: Name): List<PropertyDescriptor> =
        memberScope.getContributedVariables(name, NoLookupLocation.FROM_BACKEND).toList()

    internal fun getClass(fqName: FqName): ClassDescriptor =
        findClass(context.module.getPackage(fqName.parent()).memberScope, fqName.shortName())

    internal fun getProperty(fqName: FqName): PropertyDescriptor =
        findProperty(context.module.getPackage(fqName.parent()).memberScope, fqName.shortName()).single()

    private fun getFunction(name: String, ownerPackage: PackageViewDescriptor): IrSimpleFunctionSymbol {
        return maybeGetFunction(name, ownerPackage) ?: throw IllegalArgumentException("Function $name not found")
    }

    private fun maybeGetFunction(name: String, ownerPackage: PackageViewDescriptor): IrSimpleFunctionSymbol? {
        val tmp = findFunctions(ownerPackage.memberScope, Name.identifier(name))
        if (tmp.isEmpty())
            return null
        return symbolTable.referenceSimpleFunction(tmp.single())
    }

    private fun getInternalFunction(name: String) = getFunction(name, wasmInternalPackage)

    private fun getIrClass(fqName: FqName): IrClassSymbol = symbolTable.referenceClass(getClass(fqName))
    private fun getInternalClass(name: String): IrClassSymbol = getIrClass(FqName("kotlin.wasm.internal.$name"))
    fun getKFunctionType(type: IrType, list: List<IrType>): IrType {
        return irBuiltIns.functionN(list.size).typeWith(list + type)
    }
}
