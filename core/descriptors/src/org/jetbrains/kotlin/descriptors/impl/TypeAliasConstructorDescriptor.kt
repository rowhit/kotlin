/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.descriptors.impl

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor.Kind
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.TypeSubstitutor
import org.jetbrains.kotlin.types.Variance

interface TypeAliasConstructorDescriptor : ConstructorDescriptor {
    val typeAliasDescriptor: TypeAliasDescriptor
    val underlyingConstructorDescriptor: ConstructorDescriptor
}

class TypeAliasConstructorDescriptorImpl private constructor(
        override val typeAliasDescriptor: TypeAliasDescriptor,
        override val underlyingConstructorDescriptor: ConstructorDescriptor,
        containingDeclaration: ClassDescriptor,
        original: TypeAliasConstructorDescriptor?,
        annotations: Annotations,
        primary: Boolean,
        kind: Kind,
        source: SourceElement
) : TypeAliasConstructorDescriptor,
        ConstructorDescriptorImpl(containingDeclaration, original, annotations,
                                  primary, kind, source)
{
    override fun getOriginal(): TypeAliasConstructorDescriptor =
            super.getOriginal() as TypeAliasConstructorDescriptor

    override fun substitute(substitutor: TypeSubstitutor): TypeAliasConstructorDescriptor =
            super.substitute(substitutor) as TypeAliasConstructorDescriptor

    override fun copy(
            newOwner: DeclarationDescriptor,
            modality: Modality,
            visibility: Visibility,
            kind: Kind,
            copyOverrides: Boolean
    ): TypeAliasConstructorDescriptor =
            newCopyBuilder()
                    .setOwner(newOwner)
                    .setModality(modality)
                    .setVisibility(visibility)
                    .setKind(kind)
                    .setCopyOverrides(copyOverrides)
                    .build() as TypeAliasConstructorDescriptor

    override fun createSubstitutedCopy(
            newOwner: DeclarationDescriptor,
            original: FunctionDescriptor?,
            kind: Kind,
            newName: Name?,
            annotations: Annotations,
            source: SourceElement
    ): TypeAliasConstructorDescriptorImpl {
        assert(kind == Kind.DECLARATION || kind == Kind.SYNTHESIZED) {
            "Creating a type alias constructor that is not a declaration: \ncopy from: ${this}\nnewOwner: $newOwner\nkind: $kind"
        }
        assert(newName == null) { "Renaming type alias constructor: $this" }
        return TypeAliasConstructorDescriptorImpl(
                typeAliasDescriptor, underlyingConstructorDescriptor,
                newOwner as ClassDescriptor,
                this,
                annotations, isPrimary, Kind.DECLARATION, source)
    }

    companion object {
        fun create(
                typeAliasDescriptor: TypeAliasDescriptor,
                underlyingConstructor: ConstructorDescriptor,
                substitutor: TypeSubstitutor
        ): TypeAliasConstructorDescriptor? = with(underlyingConstructor) {
            val typeAliasConstructor =
                    TypeAliasConstructorDescriptorImpl(typeAliasDescriptor, underlyingConstructor, containingDeclaration, null,
                                                       annotations, isPrimary, kind, typeAliasDescriptor.source)

            val valueParameters =
                    FunctionDescriptorImpl.getSubstitutedValueParameters(typeAliasConstructor, valueParameters, substitutor, false)
                    ?: return null

            typeAliasConstructor.initialize(valueParameters, visibility, typeAliasDescriptor.declaredTypeParameters)

            typeAliasConstructor.returnType = substitutor.substitute(returnType, Variance.OUT_VARIANCE) ?: return null

            typeAliasConstructor
        }
    }
}



