/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.util

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrLock
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrExternalPackageFragmentImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrScriptImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.declarations.lazy.IrLazySymbolTable
import org.jetbrains.kotlin.ir.descriptors.IrBasedClassDescriptor
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.symbols.*
import org.jetbrains.kotlin.ir.symbols.impl.*
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.isEffectivelyExternal
import org.jetbrains.kotlin.utils.threadLocal

interface ReferenceSymbolTable {
    @ObsoleteDescriptorBasedAPI
    fun referenceClass(descriptor: ClassDescriptor): IrClassSymbol
    fun referenceClass(sig: IdSignature): IrClassSymbol

    @ObsoleteDescriptorBasedAPI
    fun referenceScript(descriptor: ScriptDescriptor): IrScriptSymbol

    @ObsoleteDescriptorBasedAPI
    fun referenceConstructor(descriptor: ClassConstructorDescriptor): IrConstructorSymbol
    fun referenceConstructor(sig: IdSignature): IrConstructorSymbol

    @ObsoleteDescriptorBasedAPI
    fun referenceEnumEntry(descriptor: ClassDescriptor): IrEnumEntrySymbol
    fun referenceEnumEntry(sig: IdSignature): IrEnumEntrySymbol

    @ObsoleteDescriptorBasedAPI
    fun referenceField(descriptor: PropertyDescriptor): IrFieldSymbol
    fun referenceField(sig: IdSignature): IrFieldSymbol

    @ObsoleteDescriptorBasedAPI
    fun referenceProperty(descriptor: PropertyDescriptor): IrPropertySymbol
    fun referenceProperty(sig: IdSignature): IrPropertySymbol

    @ObsoleteDescriptorBasedAPI
    fun referenceProperty(descriptor: PropertyDescriptor, generate: () -> IrProperty): IrProperty

    @ObsoleteDescriptorBasedAPI
    fun referenceSimpleFunction(descriptor: FunctionDescriptor): IrSimpleFunctionSymbol
    fun referenceSimpleFunction(sig: IdSignature): IrSimpleFunctionSymbol

    @ObsoleteDescriptorBasedAPI
    fun referenceDeclaredFunction(descriptor: FunctionDescriptor): IrSimpleFunctionSymbol

    @ObsoleteDescriptorBasedAPI
    fun referenceValueParameter(descriptor: ParameterDescriptor): IrValueParameterSymbol

    @ObsoleteDescriptorBasedAPI
    fun referenceTypeParameter(classifier: TypeParameterDescriptor): IrTypeParameterSymbol
    fun referenceTypeParameter(sig: IdSignature): IrTypeParameterSymbol

    @ObsoleteDescriptorBasedAPI
    fun referenceScopedTypeParameter(classifier: TypeParameterDescriptor): IrTypeParameterSymbol

    @ObsoleteDescriptorBasedAPI
    fun referenceVariable(descriptor: VariableDescriptor): IrVariableSymbol

    @ObsoleteDescriptorBasedAPI
    fun referenceTypeAlias(descriptor: TypeAliasDescriptor): IrTypeAliasSymbol
    fun referenceTypeAlias(sig: IdSignature): IrTypeAliasSymbol

    fun enterScope(owner: IrSymbol)
    fun enterScope(owner: IrDeclaration)

    fun leaveScope(owner: IrSymbol)
    fun leaveScope(owner: IrDeclaration)
}

open class SymbolTable(
    val signaturer: IdSignatureComposer,
    val irFactory: IrFactory,
    val nameProvider: NameProvider = NameProvider.DEFAULT
) : ReferenceSymbolTable {

    val lock = IrLock()

    @Suppress("LeakingThis")
    val lazyWrapper = IrLazySymbolTable(this)

    @Suppress("DuplicatedCode")
    private abstract class SymbolTableBase<D : DeclarationDescriptor, B : IrSymbolOwner, S : IrBindableSymbol<D, B>>(val lock: IrLock) {
        /**
         * With the partial linkage turned on it's hard to predict whether a newly created [IrSymbol] that is added to the [SymbolTable]
         * via one of referenceXXX() calls will or won't be bound to some declaration. The latter may happen in certain cases,
         * for example when the symbol refers from an IR expression to a non-top level declaration that was removed in newer version
         * of Kotlin library (KLIB). Unless such symbol is registered as "probably unbound" it remains invisible for the linkage process.
         *
         * The optimization that allows to reference symbols without registering them as "probably unbound" is fragile. It's better
         * to avoid calling any referenceXXX(reg = false) functions. Instead, wherever it is s suitable it is recommended to use one
         * of the appropriate declareXXX() calls.
         *
         * For the future: Consider implementing the optimization once again for the new "flat" ID signatures.
         */
        val unboundSymbols = linkedSetOf<S>()

        protected abstract fun signature(descriptor: D): IdSignature?

        // TODO: consider replacing paired get/set calls by java.util.Map.computeIfAbsent() which would be more performant
        abstract fun get(d: D, sig: IdSignature?): S?
        abstract fun set(s: S)
        abstract fun get(sig: IdSignature): S?
        abstract fun set(sig: IdSignature, s: S)

        inline fun declare(d: D, createSymbol: (IdSignature?) -> S, createOwner: (S) -> B): B {
            synchronized(lock) {
                val signature = signature(d)
                val existing = get(d, signature)
                val symbol = if (existing == null) {
                    checkOriginal(d)
                    val new = createSymbol(signature)
                    set(new)
                    new
                } else {
                    unboundSymbols.remove(existing)
                    existing
                }
                return createOwnerSafe(symbol, createOwner)
            }
        }

        inline fun declare(sig: IdSignature, createSymbol: () -> S, createOwner: (S) -> B): B {
            synchronized(lock) {
                val existing = get(sig)
                val symbol = if (existing == null) {
                    createSymbol()
                } else {
                    unboundSymbols.remove(existing)
                    existing
                }
                return createOwnerSafe(symbol, createOwner)
                    .also {
                        // TODO: try to get rid of this
                        set(symbol)
                    }
            }
        }

        inline fun declareIfNotExists(d: D, createSymbol: (IdSignature?) -> S, createOwner: (S) -> B): B {
            synchronized(lock) {
                val signature = signature(d)
                val existing = get(d, signature)
                val symbol = if (existing == null) {
                    checkOriginal(d)
                    val new = createSymbol(signature)
                    set(new)
                    new
                } else {
                    if (existing.isBound) {
                        unboundSymbols.remove(existing)
                        return existing.owner
                    }
                    existing
                }
                return createOwnerSafe(symbol, createOwner)
            }
        }

        inline fun declare(sig: IdSignature, d: D?, createSymbol: () -> S, createOwner: (S) -> B): B {
            synchronized(lock) {
                val existing = get(sig)
                val symbol = if (existing == null) {
                    checkOriginal(d)
                    val new = createSymbol()
                    set(new)
                    new
                } else {
                    unboundSymbols.remove(existing)
                    existing
                }
                return createOwnerSafe(symbol, createOwner)
            }
        }

        inline fun referenced(d: D, orElse: (IdSignature?) -> S): S {
            synchronized(lock) {
                val signature = signature(d)
                return get(d, signature) ?: run {
                    checkOriginal(d)
                    val new = orElse(signature)
                    assert(unboundSymbols.add(new)) { "Symbol for $new was already referenced" }
                    set(new)
                    new
                }
            }
        }

        inline fun referenced(sig: IdSignature, orElse: () -> S): S {
            synchronized(lock) {
                return get(sig) ?: run {
                    val new = orElse()
                    assert(unboundSymbols.add(new)) { "Symbol for ${new.signature} was already referenced" }
                    set(new)
                    new
                }
            }
        }

        private fun checkOriginal(d: D?) {
            @Suppress("UNCHECKED_CAST")
            val d0 = d?.original as D
            assert(d0 === d) {
                "Non-original descriptor in declaration: $d\n\tExpected: $d0"
            }
        }

        protected inline fun createOwnerSafe(symbol: S, createOwner: (S) -> B): B {
            val owner = createOwner(symbol)
            assert(symbol.isBound /*&& symbol.owner === owner*/) // TODO: unmute the second condition immediately when KT-57049 is fixed
            return owner
        }
    }

    private open inner class FlatSymbolTable<D : DeclarationDescriptor, B : IrSymbolOwner, S : IrBindableSymbol<D, B>> :
        SymbolTableBase<D, B, S>(lock) {
        val descriptorToSymbol = hashMapOf<D, S>()
        val idSigToSymbol = hashMapOf<IdSignature, S>()

        override fun signature(descriptor: D): IdSignature? =
            signaturer.composeSignature(descriptor)

        override fun get(d: D, sig: IdSignature?): S? =
            if (sig != null) {
                idSigToSymbol[sig]
            } else {
                descriptorToSymbol[d]
            }

        @OptIn(ObsoleteDescriptorBasedAPI::class)
        override fun set(s: S) {
            val signature = s.signature
            if (signature != null) {
                idSigToSymbol[signature] = s
            } else if (s.hasDescriptor) {
                descriptorToSymbol[s.descriptor] = s
            }
        }

        override fun get(sig: IdSignature): S? = idSigToSymbol[sig]

        override fun set(sig: IdSignature, s: S) {
            idSigToSymbol[sig] = s
        }
    }

    private inner class EnumEntrySymbolTable : FlatSymbolTable<ClassDescriptor, IrEnumEntry, IrEnumEntrySymbol>() {
        override fun signature(descriptor: ClassDescriptor): IdSignature? = signaturer.composeEnumEntrySignature(descriptor)
    }

    private inner class FieldSymbolTable : FlatSymbolTable<PropertyDescriptor, IrField, IrFieldSymbol>() {
        override fun signature(descriptor: PropertyDescriptor): IdSignature? = signaturer.composeFieldSignature(descriptor)
    }

    private inner class ScopedSymbolTable<D : DeclarationDescriptor, B : IrSymbolOwner, S : IrBindableSymbol<D, B>>
        : SymbolTableBase<D, B, S>(lock) {
        inner class Scope(val owner: IrSymbol, val parent: Scope?) {
            private val descriptorToSymbol = hashMapOf<D, S>()
            private val idSigToSymbol = hashMapOf<IdSignature, S>()

            private fun getByDescriptor(d: D): S? =
                descriptorToSymbol[d] ?: parent?.getByDescriptor(d)

            private fun getByIdSignature(sig: IdSignature): S? =
                idSigToSymbol[sig] ?: parent?.getByIdSignature(sig)

            operator fun get(d: D, sig: IdSignature?): S? =
                if (sig != null) {
                    getByIdSignature(sig)
                } else {
                    getByDescriptor(d)
                }

            fun getLocal(d: D) = descriptorToSymbol[d]

            @OptIn(ObsoleteDescriptorBasedAPI::class)
            operator fun set(d: D, s: S) {
                s.signature?.let {
                    require(d is TypeParameterDescriptor)
                    idSigToSymbol[it] = s
                } ?: run {
                    assert(s.hasDescriptor)
                    descriptorToSymbol[s.descriptor] = s
                }
            }

            operator fun get(sig: IdSignature): S? =
                idSigToSymbol[sig] ?: parent?.get(sig)

            operator fun set(sig: IdSignature, s: S) {
                idSigToSymbol[sig] = s
            }

            fun dumpTo(stringBuilder: StringBuilder): StringBuilder =
                stringBuilder.also {
                    it.append("owner=")
                    it.append(owner)
                    it.append("; ")
                    descriptorToSymbol.keys.joinTo(prefix = "[", postfix = "]", buffer = it)
                    it.append('\n')
                    parent?.dumpTo(it)
                }

            fun dump(): String = dumpTo(StringBuilder()).toString()
        }

        private var currentScope: Scope? = null

        override fun signature(descriptor: D): IdSignature? =
            signaturer.composeSignature(descriptor)

        override fun get(d: D, sig: IdSignature?): S? {
            val scope = currentScope ?: return null
            return scope[d, sig]
        }

        @OptIn(ObsoleteDescriptorBasedAPI::class)
        override fun set(s: S) {
            val scope = currentScope ?: throw AssertionError("No active scope")
            scope[s.descriptor] = s
        }

        override fun get(sig: IdSignature): S? {
            val scope = currentScope ?: return null
            return scope[sig]
        }

        override fun set(sig: IdSignature, s: S) {
            val scope = currentScope ?: throw AssertionError("No active scope")
            scope[sig] = s
        }

        inline fun declareLocal(d: D, createSymbol: () -> S, createOwner: (S) -> B): B {
            val scope = currentScope ?: throw AssertionError("No active scope")
            val symbol = scope.getLocal(d) ?: createSymbol().also { scope[d] = it }
            return createOwnerSafe(symbol, createOwner)
        }

        fun introduceLocal(descriptor: D, symbol: S) {
            val scope = currentScope ?: throw AssertionError("No active scope")
            scope[descriptor, signature(descriptor)]?.let {
                throw AssertionError("$descriptor is already bound to $it")
            }
            scope[descriptor] = symbol
        }

        fun enterScope(owner: IrSymbol) {
            currentScope = Scope(owner, currentScope)
        }

        fun leaveScope(owner: IrSymbol) {
            currentScope?.owner.let {
                assert(it == owner) { "Unexpected leaveScope: owner=$owner, currentScope.owner=$it" }
            }

            currentScope = currentScope?.parent

            if (currentScope != null && unboundSymbols.isNotEmpty()) {
                @OptIn(ObsoleteDescriptorBasedAPI::class)
                throw AssertionError("Local scope contains unbound symbols: ${unboundSymbols.joinToString { it.descriptor.toString() }}")
            }
        }

        fun dump(): String =
            currentScope?.dump() ?: "<none>"
    }

    private val externalPackageFragmentTable =
        FlatSymbolTable<PackageFragmentDescriptor, IrExternalPackageFragment, IrExternalPackageFragmentSymbol>()
    private val scriptSymbolTable = FlatSymbolTable<ScriptDescriptor, IrScript, IrScriptSymbol>()
    private val classSymbolTable = FlatSymbolTable<ClassDescriptor, IrClass, IrClassSymbol>()
    private val constructorSymbolTable = FlatSymbolTable<ClassConstructorDescriptor, IrConstructor, IrConstructorSymbol>()
    private val enumEntrySymbolTable = EnumEntrySymbolTable()
    private val fieldSymbolTable = FieldSymbolTable()
    private val simpleFunctionSymbolTable = FlatSymbolTable<FunctionDescriptor, IrSimpleFunction, IrSimpleFunctionSymbol>()
    private val propertySymbolTable = FlatSymbolTable<PropertyDescriptor, IrProperty, IrPropertySymbol>()
    private val typeAliasSymbolTable = FlatSymbolTable<TypeAliasDescriptor, IrTypeAlias, IrTypeAliasSymbol>()

    private val globalTypeParameterSymbolTable = FlatSymbolTable<TypeParameterDescriptor, IrTypeParameter, IrTypeParameterSymbol>()
    private val scopedTypeParameterSymbolTable by threadLocal {
        ScopedSymbolTable<TypeParameterDescriptor, IrTypeParameter, IrTypeParameterSymbol>()
    }
    private val valueParameterSymbolTable by threadLocal {
        ScopedSymbolTable<ParameterDescriptor, IrValueParameter, IrValueParameterSymbol>()
    }
    private val variableSymbolTable by threadLocal {
        ScopedSymbolTable<VariableDescriptor, IrVariable, IrVariableSymbol>()
    }
    private val localDelegatedPropertySymbolTable by threadLocal {
        ScopedSymbolTable<VariableDescriptorWithAccessors, IrLocalDelegatedProperty, IrLocalDelegatedPropertySymbol>()
    }
    private val scopedSymbolTables by threadLocal {
        listOf(valueParameterSymbolTable, variableSymbolTable, scopedTypeParameterSymbolTable, localDelegatedPropertySymbolTable)
    }

    @ObsoleteDescriptorBasedAPI
    fun referenceExternalPackageFragment(descriptor: PackageFragmentDescriptor) =
        externalPackageFragmentTable.referenced(descriptor) { IrExternalPackageFragmentSymbolImpl(descriptor) }

    @ObsoleteDescriptorBasedAPI
    fun declareExternalPackageFragment(descriptor: PackageFragmentDescriptor): IrExternalPackageFragment {
        return externalPackageFragmentTable.declare(
            descriptor,
            { IrExternalPackageFragmentSymbolImpl(descriptor) },
            { IrExternalPackageFragmentImpl(it, descriptor.fqName) }
        )
    }

    @ObsoleteDescriptorBasedAPI
    fun declareExternalPackageFragmentIfNotExists(descriptor: PackageFragmentDescriptor): IrExternalPackageFragment {
        return externalPackageFragmentTable.declareIfNotExists(
            descriptor,
            { IrExternalPackageFragmentSymbolImpl(descriptor) },
            { IrExternalPackageFragmentImpl(it, descriptor.fqName) }
        )
    }

    @ObsoleteDescriptorBasedAPI
    fun declareAnonymousInitializer(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: ClassDescriptor
    ): IrAnonymousInitializer =
        irFactory.createAnonymousInitializer(
            startOffset, endOffset, origin,
            IrAnonymousInitializerSymbolImpl(descriptor)
        )

    @ObsoleteDescriptorBasedAPI
    fun declareScript(
        startOffset: Int,
        endOffset: Int,
        descriptor: ScriptDescriptor,
        scriptFactory: (IrScriptSymbol) -> IrScript = { symbol: IrScriptSymbol ->
            IrScriptImpl(symbol, nameProvider.nameForDeclaration(descriptor), irFactory, startOffset, endOffset)
        }
    ): IrScript {
        return scriptSymbolTable.declare(
            descriptor,
            { IrScriptSymbolImpl(descriptor) },
            scriptFactory
        )
    }

    fun declareScript(
        sig: IdSignature,
        symbolFactory: () -> IrScriptSymbol,
        scriptFactory: (IrScriptSymbol) -> IrScript
    ): IrScript {
        return scriptSymbolTable.declare(
            sig,
            symbolFactory,
            scriptFactory
        )
    }

    @ObsoleteDescriptorBasedAPI
    override fun referenceScript(descriptor: ScriptDescriptor): IrScriptSymbol =
        scriptSymbolTable.referenced(descriptor) { IrScriptSymbolImpl(descriptor) }

    private fun createClassSymbol(descriptor: ClassDescriptor, signature: IdSignature?): IrClassSymbol =
        signature?.let { IrClassPublicSymbolImpl(it, descriptor) } ?: IrClassSymbolImpl(descriptor)

    @ObsoleteDescriptorBasedAPI
    fun declareClass(
        descriptor: ClassDescriptor,
        classFactory: (IrClassSymbol) -> IrClass
    ): IrClass {
        return classSymbolTable.declare(
            descriptor,
            { createClassSymbol(descriptor, signaturer.composeSignature(descriptor)) },
            classFactory
        )
    }

    fun declareClass(
        sig: IdSignature,
        symbolFactory: () -> IrClassSymbol,
        classFactory: (IrClassSymbol) -> IrClass
    ): IrClass {
        return classSymbolTable.declare(
            sig,
            symbolFactory,
            classFactory
        )
    }

    fun declareClassWithSignature(sig: IdSignature, symbol: IrClassSymbol) {
        classSymbolTable.set(sig, symbol)
    }

    @ObsoleteDescriptorBasedAPI
    fun declareClassIfNotExists(descriptor: ClassDescriptor, classFactory: (IrClassSymbol) -> IrClass): IrClass =
        classSymbolTable.declareIfNotExists(descriptor, { signature -> createClassSymbol(descriptor, signature) }, classFactory)

    // Note: used in native
    @ObsoleteDescriptorBasedAPI
    @Suppress("unused")
    fun declareClassFromLinker(descriptor: ClassDescriptor, sig: IdSignature, factory: (IrClassSymbol) -> IrClass): IrClass {
        return classSymbolTable.run {
            if (sig.isPubliclyVisible) {
                declare(sig, descriptor, { IrClassPublicSymbolImpl(sig, descriptor) }, factory)
            } else {
                declare(descriptor, { IrClassSymbolImpl(descriptor) }, factory)
            }
        }
    }

    @ObsoleteDescriptorBasedAPI
    override fun referenceClass(descriptor: ClassDescriptor): IrClassSymbol =
        @Suppress("Reformat")
        // This is needed for cases like kt46069.kt, where psi2ir creates descriptor-less IR elements for adapted function references.
        // In JVM IR, symbols are linked via descriptors by default, so for an adapted function reference, an IrBasedClassDescriptor
        // is created for any classifier used in the function parameter/return types. Any attempt to translate such type to IrType goes
        // to this method, which puts the descriptor into unboundClasses, which causes an assertion failure later because we won't bind
        // such symbol anywhere.
        // TODO: maybe there's a better solution.
        if (descriptor is IrBasedClassDescriptor)
            descriptor.owner.symbol
        else
            classSymbolTable.referenced(descriptor) { signature -> createClassSymbol(descriptor, signature) }

    fun referenceClass(
        sig: IdSignature,
        symbolFactory: () -> IrClassSymbol,
        classFactory: (IrClassSymbol) -> IrClass
    ) =
        classSymbolTable.referenced(sig) { declareClass(sig, symbolFactory, classFactory).symbol }

    override fun referenceClass(sig: IdSignature): IrClassSymbol =
        classSymbolTable.run {
            if (sig.isPubliclyVisible) referenced(sig) { IrClassPublicSymbolImpl(sig) }
            else IrClassSymbolImpl().also {
                it.privateSignature = sig
            }
        }

    val unboundClasses: Set<IrClassSymbol> get() = classSymbolTable.unboundSymbols

    private fun createConstructorSymbol(descriptor: ClassConstructorDescriptor, signature: IdSignature?): IrConstructorSymbol =
        signature?.let { IrConstructorPublicSymbolImpl(it, descriptor) } ?: IrConstructorSymbolImpl(descriptor)

    @ObsoleteDescriptorBasedAPI
    fun declareConstructor(
        descriptor: ClassConstructorDescriptor,
        constructorFactory: (IrConstructorSymbol) -> IrConstructor
    ): IrConstructor =
        constructorSymbolTable.declare(
            descriptor,
            { signature -> createConstructorSymbol(descriptor, signature) },
            constructorFactory
        )

    fun declareConstructor(
        sig: IdSignature,
        symbolFactory: () -> IrConstructorSymbol,
        constructorFactory: (IrConstructorSymbol) -> IrConstructor
    ): IrConstructor {
        return constructorSymbolTable.declare(
            sig,
            symbolFactory,
            constructorFactory
        )
    }

    @ObsoleteDescriptorBasedAPI
    fun declareConstructorIfNotExists(
        descriptor: ClassConstructorDescriptor,
        constructorFactory: (IrConstructorSymbol) -> IrConstructor,
    ): IrConstructor =
        constructorSymbolTable.declareIfNotExists(
            descriptor,
            { signature -> createConstructorSymbol(descriptor, signature) },
            constructorFactory
        )

    fun declareConstructorWithSignature(sig: IdSignature, symbol: IrConstructorSymbol) {
        constructorSymbolTable.set(sig, symbol)
    }

    @ObsoleteDescriptorBasedAPI
    override fun referenceConstructor(descriptor: ClassConstructorDescriptor): IrConstructorSymbol =
        constructorSymbolTable.referenced(descriptor) { signature -> createConstructorSymbol(descriptor, signature) }

    fun referenceConstructorIfAny(sig: IdSignature): IrConstructorSymbol? =
        constructorSymbolTable.get(sig)

    // Note: used in native
    @ObsoleteDescriptorBasedAPI
    @Suppress("unused")
    fun declareConstructorFromLinker(
        descriptor: ClassConstructorDescriptor,
        sig: IdSignature,
        constructorFactory: (IrConstructorSymbol) -> IrConstructor
    ): IrConstructor {
        return constructorSymbolTable.run {
            if (sig.isPubliclyVisible) {
                declare(sig, descriptor, { IrConstructorPublicSymbolImpl(sig, descriptor) }, constructorFactory)
            } else {
                declare(descriptor, { IrConstructorSymbolImpl(descriptor) }, constructorFactory)
            }
        }
    }

    override fun referenceConstructor(sig: IdSignature): IrConstructorSymbol =
        constructorSymbolTable.run {
            if (sig.isPubliclyVisible) referenced(sig) { IrConstructorPublicSymbolImpl(sig) }
            else IrConstructorSymbolImpl()
        }

    val unboundConstructors: Set<IrConstructorSymbol> get() = constructorSymbolTable.unboundSymbols

    private fun createEnumEntrySymbol(descriptor: ClassDescriptor, signature: IdSignature?): IrEnumEntrySymbol =
        signature?.let { IrEnumEntryPublicSymbolImpl(it, descriptor) } ?: IrEnumEntrySymbolImpl(descriptor)

    @ObsoleteDescriptorBasedAPI
    fun declareEnumEntry(
        startOffset: Int, endOffset: Int, origin: IrDeclarationOrigin, descriptor: ClassDescriptor,
        factory: (IrEnumEntrySymbol) -> IrEnumEntry = {
            irFactory.createEnumEntry(startOffset, endOffset, origin, it, nameProvider.nameForDeclaration(descriptor))
        }
    ): IrEnumEntry =
        enumEntrySymbolTable.declare(
            descriptor,
            { signature -> createEnumEntrySymbol(descriptor, signature) },
            factory
        )

    fun declareEnumEntry(
        sig: IdSignature,
        symbolFactory: () -> IrEnumEntrySymbol,
        enumEntryFactory: (IrEnumEntrySymbol) -> IrEnumEntry
    ): IrEnumEntry = enumEntrySymbolTable.declare(sig, symbolFactory, enumEntryFactory)

    @ObsoleteDescriptorBasedAPI
    fun declareEnumEntryIfNotExists(descriptor: ClassDescriptor, factory: (IrEnumEntrySymbol) -> IrEnumEntry): IrEnumEntry =
        enumEntrySymbolTable.declareIfNotExists(descriptor, { signature -> createEnumEntrySymbol(descriptor, signature) }, factory)

    // Note: used in native
    @ObsoleteDescriptorBasedAPI
    @Suppress("unused")
    fun declareEnumEntryFromLinker(
        descriptor: ClassDescriptor,
        sig: IdSignature,
        factory: (IrEnumEntrySymbol) -> IrEnumEntry
    ): IrEnumEntry {
        return enumEntrySymbolTable.run {
            if (sig.isPubliclyVisible) {
                declare(sig, descriptor, { IrEnumEntryPublicSymbolImpl(sig, descriptor) }, factory)
            } else {
                declare(descriptor, { IrEnumEntrySymbolImpl(descriptor) }, factory)
            }
        }
    }

    @ObsoleteDescriptorBasedAPI
    override fun referenceEnumEntry(descriptor: ClassDescriptor): IrEnumEntrySymbol =
        enumEntrySymbolTable.referenced(descriptor) { signature -> createEnumEntrySymbol(descriptor, signature) }

    override fun referenceEnumEntry(sig: IdSignature) =
        enumEntrySymbolTable.run {
            if (sig.isPubliclyVisible) referenced(sig) { IrEnumEntryPublicSymbolImpl(sig) }
            else IrEnumEntrySymbolImpl()
        }

    val unboundEnumEntries: Set<IrEnumEntrySymbol> get() = enumEntrySymbolTable.unboundSymbols

    private fun createFieldSymbol(descriptor: PropertyDescriptor, signature: IdSignature?): IrFieldSymbol =
        signature?.let { IrFieldPublicSymbolImpl(it, descriptor) } ?: IrFieldSymbolImpl(descriptor)

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    fun declareField(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: PropertyDescriptor,
        type: IrType,
        visibility: DescriptorVisibility? = null,
        fieldFactory: (IrFieldSymbol) -> IrField = {
            irFactory.createField(
                startOffset, endOffset, origin, it, nameProvider.nameForDeclaration(descriptor), type,
                visibility ?: it.descriptor.visibility, !it.descriptor.isVar, it.descriptor.isEffectivelyExternal(),
                it.descriptor.dispatchReceiverParameter == null
            ).apply {
                metadata = DescriptorMetadataSource.Property(it.descriptor)
            }
        }
    ): IrField =
        fieldSymbolTable.declare(
            descriptor,
            { signature -> createFieldSymbol(descriptor, signature) },
            fieldFactory
        )

    @ObsoleteDescriptorBasedAPI
    fun declareField(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: PropertyDescriptor,
        type: IrType,
        irInitializer: IrExpressionBody?
    ): IrField =
        declareField(startOffset, endOffset, origin, descriptor, type).apply {
            initializer = irInitializer
        }

    fun declareField(
        sig: IdSignature,
        symbolFactory: () -> IrFieldSymbol,
        propertyFactory: (IrFieldSymbol) -> IrField
    ): IrField {
        return fieldSymbolTable.declare(
            sig,
            symbolFactory,
            propertyFactory
        )
    }

    fun declareFieldWithSignature(sig: IdSignature, symbol: IrFieldSymbol) {
        fieldSymbolTable.set(sig, symbol)
    }

    @ObsoleteDescriptorBasedAPI
    override fun referenceField(descriptor: PropertyDescriptor): IrFieldSymbol =
        fieldSymbolTable.referenced(descriptor) { signature -> createFieldSymbol(descriptor, signature) }

    override fun referenceField(sig: IdSignature): IrFieldSymbol =
        fieldSymbolTable.run {
            if (sig.isPubliclyVisible) {
                referenced(sig) { IrFieldPublicSymbolImpl(sig) }
            } else IrFieldSymbolImpl()
        }

    val unboundFields: Set<IrFieldSymbol> get() = fieldSymbolTable.unboundSymbols

    @Deprecated(message = "Use declareProperty/referenceProperty", level = DeprecationLevel.WARNING)
    val propertyTable = HashMap<PropertyDescriptor, IrProperty>()

    @ObsoleteDescriptorBasedAPI
    override fun referenceProperty(descriptor: PropertyDescriptor, generate: () -> IrProperty): IrProperty =
        @Suppress("DEPRECATION")
        propertyTable.getOrPut(descriptor, generate)

    private fun createPropertySymbol(descriptor: PropertyDescriptor, signature: IdSignature?): IrPropertySymbol =
        signature?.let { IrPropertyPublicSymbolImpl(it, descriptor) } ?: IrPropertySymbolImpl(descriptor)

    @ObsoleteDescriptorBasedAPI
    fun declareProperty(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: PropertyDescriptor,
        isDelegated: Boolean = descriptor.isDelegated,
        propertyFactory: (IrPropertySymbol) -> IrProperty = { symbol ->
            irFactory.createProperty(
                startOffset, endOffset, origin, symbol, name = nameProvider.nameForDeclaration(descriptor),
                visibility = descriptor.visibility,
                modality = descriptor.modality,
                isVar = descriptor.isVar,
                isConst = descriptor.isConst,
                isLateinit = descriptor.isLateInit,
                isDelegated = isDelegated,
                isExternal = descriptor.isEffectivelyExternal(),
                isExpect = descriptor.isExpect,
                isFakeOverride = descriptor.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE
            ).apply {
                metadata = DescriptorMetadataSource.Property(symbol.descriptor)
            }
        }
    ): IrProperty =
        propertySymbolTable.declare(
            descriptor,
            { signature -> createPropertySymbol(descriptor, signature) },
            propertyFactory
        )

    fun declareProperty(
        sig: IdSignature,
        symbolFactory: () -> IrPropertySymbol,
        propertyFactory: (IrPropertySymbol) -> IrProperty
    ): IrProperty {
        return propertySymbolTable.declare(
            sig,
            symbolFactory,
            propertyFactory
        )
    }

    @ObsoleteDescriptorBasedAPI
    fun declarePropertyIfNotExists(descriptor: PropertyDescriptor, propertyFactory: (IrPropertySymbol) -> IrProperty): IrProperty =
        propertySymbolTable.declareIfNotExists(descriptor, { signature -> createPropertySymbol(descriptor, signature) }, propertyFactory)

    // Note: used in native
    @ObsoleteDescriptorBasedAPI
    @Suppress("unused")
    fun declarePropertyFromLinker(descriptor: PropertyDescriptor, sig: IdSignature, factory: (IrPropertySymbol) -> IrProperty): IrProperty {
        return propertySymbolTable.run {
            if (sig.isPubliclyVisible) {
                declare(sig, descriptor, { IrPropertyPublicSymbolImpl(sig, descriptor) }, factory)
            } else {
                declare(descriptor, { IrPropertySymbolImpl(descriptor) }, factory)
            }
        }
    }

    fun declarePropertyWithSignature(sig: IdSignature, symbol: IrPropertySymbol) {
        propertySymbolTable.set(sig, symbol)
    }

    @ObsoleteDescriptorBasedAPI
    override fun referenceProperty(descriptor: PropertyDescriptor): IrPropertySymbol =
        propertySymbolTable.referenced(descriptor) { signature -> createPropertySymbol(descriptor, signature) }

    fun referencePropertyIfAny(sig: IdSignature): IrPropertySymbol? =
        propertySymbolTable.get(sig)

    override fun referenceProperty(sig: IdSignature): IrPropertySymbol =
        propertySymbolTable.run {
            if (sig.isPubliclyVisible) referenced(sig) { IrPropertyPublicSymbolImpl(sig) }
            else IrPropertySymbolImpl()
        }

    val unboundProperties: Set<IrPropertySymbol> get() = propertySymbolTable.unboundSymbols

    private fun createTypeAliasSymbol(descriptor: TypeAliasDescriptor, signature: IdSignature?): IrTypeAliasSymbol =
        signature?.let { IrTypeAliasPublicSymbolImpl(it, descriptor) } ?: IrTypeAliasSymbolImpl(descriptor)

    @ObsoleteDescriptorBasedAPI
    override fun referenceTypeAlias(descriptor: TypeAliasDescriptor): IrTypeAliasSymbol =
        typeAliasSymbolTable.referenced(descriptor) { signature -> createTypeAliasSymbol(descriptor, signature) }

    override fun referenceTypeAlias(sig: IdSignature) =
        typeAliasSymbolTable.run {
            if (sig.isPubliclyVisible) referenced(sig) { IrTypeAliasPublicSymbolImpl(sig) }
            else IrTypeAliasSymbolImpl()
        }

    @ObsoleteDescriptorBasedAPI
    fun declareTypeAlias(descriptor: TypeAliasDescriptor, factory: (IrTypeAliasSymbol) -> IrTypeAlias): IrTypeAlias =
        typeAliasSymbolTable.declare(descriptor, { signature -> createTypeAliasSymbol(descriptor, signature) }, factory)

    fun declareTypeAlias(
        sig: IdSignature,
        symbolFactory: () -> IrTypeAliasSymbol,
        factory: (IrTypeAliasSymbol) -> IrTypeAlias
    ): IrTypeAlias {
        return typeAliasSymbolTable.declare(sig, symbolFactory, factory)
    }

    @ObsoleteDescriptorBasedAPI
    fun declareTypeAliasIfNotExists(descriptor: TypeAliasDescriptor, factory: (IrTypeAliasSymbol) -> IrTypeAlias): IrTypeAlias =
        typeAliasSymbolTable.declareIfNotExists(descriptor, { signature -> createTypeAliasSymbol(descriptor, signature) }, factory)

    val unboundTypeAliases: Set<IrTypeAliasSymbol> get() = typeAliasSymbolTable.unboundSymbols

    private fun createSimpleFunctionSymbol(descriptor: FunctionDescriptor, signature: IdSignature?): IrSimpleFunctionSymbol =
        signature?.let { IrSimpleFunctionPublicSymbolImpl(it, descriptor) } ?: IrSimpleFunctionSymbolImpl(descriptor)

    @ObsoleteDescriptorBasedAPI
    fun declareSimpleFunction(
        descriptor: FunctionDescriptor,
        functionFactory: (IrSimpleFunctionSymbol) -> IrSimpleFunction
    ): IrSimpleFunction {
        return simpleFunctionSymbolTable.declare(
            descriptor,
            { signature -> createSimpleFunctionSymbol(descriptor, signature) },
            functionFactory
        )
    }

    fun declareSimpleFunction(
        sig: IdSignature,
        symbolFactory: () -> IrSimpleFunctionSymbol,
        functionFactory: (IrSimpleFunctionSymbol) -> IrSimpleFunction
    ): IrSimpleFunction {
        return simpleFunctionSymbolTable.declare(
            sig,
            symbolFactory,
            functionFactory
        )
    }

    @ObsoleteDescriptorBasedAPI
    fun declareSimpleFunctionIfNotExists(
        descriptor: FunctionDescriptor,
        functionFactory: (IrSimpleFunctionSymbol) -> IrSimpleFunction
    ): IrSimpleFunction =
        simpleFunctionSymbolTable.declareIfNotExists(
            descriptor, { signature -> createSimpleFunctionSymbol(descriptor, signature) }, functionFactory
        )

    // Note: used in native
    @ObsoleteDescriptorBasedAPI
    @Suppress("unused")
    fun declareSimpleFunctionFromLinker(
        descriptor: FunctionDescriptor?,
        sig: IdSignature,
        functionFactory: (IrSimpleFunctionSymbol) -> IrSimpleFunction
    ): IrSimpleFunction {
        return simpleFunctionSymbolTable.run {
            if (sig.isPubliclyVisible) {
                declare(sig, descriptor, { IrSimpleFunctionPublicSymbolImpl(sig, descriptor) }, functionFactory)
            } else {
                declare(descriptor!!, { IrSimpleFunctionSymbolImpl(descriptor) }, functionFactory)
            }
        }
    }

    fun declareSimpleFunctionWithSignature(sig: IdSignature, symbol: IrSimpleFunctionSymbol) {
        simpleFunctionSymbolTable.set(sig, symbol)
    }

    @ObsoleteDescriptorBasedAPI
    override fun referenceSimpleFunction(descriptor: FunctionDescriptor): IrSimpleFunctionSymbol =
        simpleFunctionSymbolTable.referenced(descriptor) { signature -> createSimpleFunctionSymbol(descriptor, signature) }

    fun referenceSimpleFunctionIfAny(sig: IdSignature): IrSimpleFunctionSymbol? =
        simpleFunctionSymbolTable.get(sig)

    override fun referenceSimpleFunction(sig: IdSignature): IrSimpleFunctionSymbol {
        return simpleFunctionSymbolTable.run {
            if (sig.isPubliclyVisible) referenced(sig) { IrSimpleFunctionPublicSymbolImpl(sig) }
            else IrSimpleFunctionSymbolImpl().also {
                it.privateSignature = sig
            }
        }
    }

    @ObsoleteDescriptorBasedAPI
    override fun referenceDeclaredFunction(descriptor: FunctionDescriptor) =
        simpleFunctionSymbolTable.referenced(descriptor) { throw AssertionError("Function is not declared: $descriptor") }

    val unboundSimpleFunctions: Set<IrSimpleFunctionSymbol> get() = simpleFunctionSymbolTable.unboundSymbols

    private fun createTypeParameterSymbol(descriptor: TypeParameterDescriptor, signature: IdSignature?): IrTypeParameterSymbol =
        signature?.let { IrTypeParameterPublicSymbolImpl(it, descriptor) } ?: IrTypeParameterSymbolImpl(descriptor)

    @ObsoleteDescriptorBasedAPI
    fun declareGlobalTypeParameter(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: TypeParameterDescriptor,
        typeParameterFactory: (IrTypeParameterSymbol) -> IrTypeParameter = {
            irFactory.createTypeParameter(
                startOffset, endOffset, origin, it, nameProvider.nameForDeclaration(descriptor),
                it.descriptor.index, it.descriptor.isReified, it.descriptor.variance
            )
        }
    ): IrTypeParameter =
        globalTypeParameterSymbolTable.declare(
            descriptor,
            { signature -> createTypeParameterSymbol(descriptor, signature) },
            typeParameterFactory
        )

    fun declareGlobalTypeParameter(
        sig: IdSignature,
        symbolFactory: () -> IrTypeParameterSymbol,
        typeParameterFactory: (IrTypeParameterSymbol) -> IrTypeParameter
    ): IrTypeParameter {
        return globalTypeParameterSymbolTable.declare(sig, symbolFactory, typeParameterFactory)
    }

    @ObsoleteDescriptorBasedAPI
    fun declareScopedTypeParameter(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: TypeParameterDescriptor,
        typeParameterFactory: (IrTypeParameterSymbol) -> IrTypeParameter = {
            irFactory.createTypeParameter(
                startOffset, endOffset, origin, it, nameProvider.nameForDeclaration(descriptor),
                it.descriptor.index, it.descriptor.isReified, it.descriptor.variance
            )
        }
    ): IrTypeParameter =
        scopedTypeParameterSymbolTable.declare(
            descriptor,
            { signature -> createTypeParameterSymbol(descriptor, signature) },
            typeParameterFactory
        )

    fun declareScopedTypeParameter(
        sig: IdSignature,
        symbolFactory: (IdSignature) -> IrTypeParameterSymbol,
        typeParameterFactory: (IrTypeParameterSymbol) -> IrTypeParameter
    ): IrTypeParameter {
        return typeParameterFactory(symbolFactory(sig))
    }

    val unboundTypeParameters: Set<IrTypeParameterSymbol> get() = globalTypeParameterSymbolTable.unboundSymbols

    @ObsoleteDescriptorBasedAPI
    fun declareValueParameter(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: ParameterDescriptor,
        type: IrType,
        varargElementType: IrType? = null,
        name: Name? = null,
        index: Int? = null,
        isAssignable: Boolean = false,
        valueParameterFactory: (IrValueParameterSymbol) -> IrValueParameter = {
            irFactory.createValueParameter(
                startOffset, endOffset, origin, it, name ?: nameProvider.nameForDeclaration(descriptor),
                index ?: descriptor.indexOrMinusOne, type, varargElementType, descriptor.isCrossinline, descriptor.isNoinline,
                isHidden = false, isAssignable = isAssignable
            )
        }
    ): IrValueParameter =
        valueParameterSymbolTable.declareLocal(
            descriptor,
            { IrValueParameterSymbolImpl(descriptor) },
            valueParameterFactory
        )

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    fun introduceValueParameter(irValueParameter: IrValueParameter) {
        valueParameterSymbolTable.introduceLocal(irValueParameter.descriptor, irValueParameter.symbol)
    }

    @ObsoleteDescriptorBasedAPI
    override fun referenceValueParameter(descriptor: ParameterDescriptor) =
        valueParameterSymbolTable.referenced(descriptor) {
            throw AssertionError("Undefined parameter referenced: $descriptor\n${valueParameterSymbolTable.dump()}")
        }

    @ObsoleteDescriptorBasedAPI
    override fun referenceTypeParameter(classifier: TypeParameterDescriptor): IrTypeParameterSymbol =
        scopedTypeParameterSymbolTable.get(classifier, signaturer.composeSignature(classifier))
            ?: globalTypeParameterSymbolTable.referenced(classifier) { signature -> createTypeParameterSymbol(classifier, signature) }

    @ObsoleteDescriptorBasedAPI
    override fun referenceScopedTypeParameter(classifier: TypeParameterDescriptor): IrTypeParameterSymbol =
        scopedTypeParameterSymbolTable.referenced(classifier) { signature -> createTypeParameterSymbol(classifier, signature) }

    override fun referenceTypeParameter(sig: IdSignature): IrTypeParameterSymbol =
        globalTypeParameterSymbolTable.referenced(sig) {
            if (sig.isPubliclyVisible) IrTypeParameterPublicSymbolImpl(sig) else IrTypeParameterSymbolImpl()
        }

    @ObsoleteDescriptorBasedAPI
    fun declareVariable(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: VariableDescriptor,
        type: IrType,
        variableFactory: (IrVariableSymbol) -> IrVariable = {
            IrVariableImpl(
                startOffset, endOffset, origin, it, nameProvider.nameForDeclaration(descriptor), type,
                descriptor.isVar, descriptor.isConst, descriptor.isLateInit
            )
        }
    ): IrVariable =
        variableSymbolTable.declareLocal(
            descriptor,
            { IrVariableSymbolImpl(descriptor) },
            variableFactory
        )

    @ObsoleteDescriptorBasedAPI
    fun declareVariable(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: VariableDescriptor,
        type: IrType,
        irInitializerExpression: IrExpression?
    ): IrVariable =
        declareVariable(startOffset, endOffset, origin, descriptor, type).apply {
            initializer = irInitializerExpression
        }

    @ObsoleteDescriptorBasedAPI
    override fun referenceVariable(descriptor: VariableDescriptor) =
        variableSymbolTable.referenced(descriptor) { throw AssertionError("Undefined variable referenced: $descriptor") }

    @ObsoleteDescriptorBasedAPI
    fun declareLocalDelegatedProperty(
        startOffset: Int,
        endOffset: Int,
        origin: IrDeclarationOrigin,
        descriptor: VariableDescriptorWithAccessors,
        type: IrType,
        factory: (IrLocalDelegatedPropertySymbol) -> IrLocalDelegatedProperty = {
            irFactory.createLocalDelegatedProperty(
                startOffset, endOffset, origin, it, nameProvider.nameForDeclaration(descriptor), type, descriptor.isVar
            )
        }
    ): IrLocalDelegatedProperty =
        localDelegatedPropertySymbolTable.declareLocal(
            descriptor,
            { IrLocalDelegatedPropertySymbolImpl(descriptor) },
            factory
        ).apply {
            metadata = DescriptorMetadataSource.LocalDelegatedProperty(descriptor)
        }

    @ObsoleteDescriptorBasedAPI
    fun referenceLocalDelegatedProperty(descriptor: VariableDescriptorWithAccessors) =
        localDelegatedPropertySymbolTable.referenced(descriptor) {
            throw AssertionError("Undefined local delegated property referenced: $descriptor")
        }

    override fun enterScope(owner: IrSymbol) {
        scopedSymbolTables.forEach { it.enterScope(owner) }
    }

    override fun enterScope(owner: IrDeclaration) {
        enterScope(owner.symbol)
    }

    override fun leaveScope(owner: IrSymbol) {
        scopedSymbolTables.forEach { it.leaveScope(owner) }
    }

    override fun leaveScope(owner: IrDeclaration) {
        leaveScope(owner.symbol)
    }

    @ObsoleteDescriptorBasedAPI
    open fun referenceValue(value: ValueDescriptor): IrValueSymbol =
        when (value) {
            is ParameterDescriptor ->
                valueParameterSymbolTable.referenced(value) { throw AssertionError("Undefined parameter referenced: $value") }
            is VariableDescriptor ->
                variableSymbolTable.referenced(value) { throw AssertionError("Undefined variable referenced: $value") }
            else ->
                throw IllegalArgumentException("Unexpected value descriptor: $value")
        }

    private inline fun <D : DeclarationDescriptor, IR : IrSymbolOwner, S : IrBindableSymbol<D, IR>> FlatSymbolTable<D, IR, S>.forEachPublicSymbolImpl(
        block: (IrSymbol) -> Unit
    ) {
        idSigToSymbol.forEach { (_, sym) ->
            assert(sym.isPublicApi)
            block(sym)
        }
    }

    fun forEachPublicSymbol(block: (IrSymbol) -> Unit) {
        classSymbolTable.forEachPublicSymbolImpl { block(it) }
        constructorSymbolTable.forEachPublicSymbolImpl { block(it) }
        simpleFunctionSymbolTable.forEachPublicSymbolImpl { block(it) }
        propertySymbolTable.forEachPublicSymbolImpl { block(it) }
        enumEntrySymbolTable.forEachPublicSymbolImpl { block(it) }
        typeAliasSymbolTable.forEachPublicSymbolImpl { block(it) }
        fieldSymbolTable.forEachPublicSymbolImpl { block(it) }
    }
}

inline fun <T> SymbolTable.withScope(owner: IrSymbol, block: SymbolTable.() -> T): T {
    enterScope(owner)
    val result = block()
    leaveScope(owner)
    return result
}

inline fun <T> SymbolTable.withScope(owner: IrDeclaration, block: SymbolTable.() -> T): T {
    enterScope(owner)
    val result = block()
    leaveScope(owner)
    return result
}

inline fun <T> ReferenceSymbolTable.withReferenceScope(owner: IrDeclaration, block: ReferenceSymbolTable.() -> T): T {
    enterScope(owner)
    val result = block()
    leaveScope(owner)
    return result
}

val SymbolTable.allUnbound: Set<IrSymbol>
    get() = buildSet {
        fun addUnbound(symbols: Collection<IrSymbol>) {
            symbols.filterTo(this) { !it.isBound }
        }

        addUnbound(unboundClasses)
        addUnbound(unboundConstructors)
        addUnbound(unboundEnumEntries)
        addUnbound(unboundFields)
        addUnbound(unboundSimpleFunctions)
        addUnbound(unboundProperties)
        addUnbound(unboundTypeAliases)
        addUnbound(unboundTypeParameters)
    }
