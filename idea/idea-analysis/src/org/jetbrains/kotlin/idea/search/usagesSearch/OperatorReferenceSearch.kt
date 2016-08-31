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

package org.jetbrains.kotlin.idea.search.usagesSearch

import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.*
import com.intellij.psi.search.*
import com.intellij.util.Processor
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.asJava.namedUnwrappedElement
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptor
import org.jetbrains.kotlin.idea.references.KtDestructuringDeclarationReference
import org.jetbrains.kotlin.idea.references.KtInvokeFunctionReference
import org.jetbrains.kotlin.idea.references.KtReference
import org.jetbrains.kotlin.idea.search.ideaExtensions.KotlinRequestResultProcessor
import org.jetbrains.kotlin.idea.search.restrictToKotlinSources
import org.jetbrains.kotlin.idea.search.usagesSearch.ExpressionsOfTypeProcessor.Companion.testLog
import org.jetbrains.kotlin.idea.util.fuzzyExtensionReceiverType
import org.jetbrains.kotlin.idea.util.toFuzzyType
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.dataClassUtils.getComponentIndex
import org.jetbrains.kotlin.resolve.descriptorUtil.isExtension
import org.jetbrains.kotlin.util.isValidOperator
import java.util.*
import kotlin.reflect.KClass

//TODO: problem with infinite recursion not solved

//TODO: code duplication

abstract class OperatorReferenceSearcher(
        private val targetDeclaration: KtDeclaration,
        private val searchScope: SearchScope,
        private val consumer: Processor<PsiReference>,
        private val optimizer: SearchRequestCollector,
        private val referenceType: KClass<out KtReference>,
        private val wordToSearch: String?
) {
    private val project = targetDeclaration.project

    protected abstract fun processSuspiciousExpression(expression: KtExpression)/* {
    }*/

    protected abstract fun isReferenceElement(element: PsiElement): Boolean

    protected fun processReferenceElement(element: PsiElement): Boolean {
        val reference = element.references.first { isOurReference(it) }
        if (reference.isReferenceTo(targetDeclaration)) {
            return consumer.process(reference)
        }
        else {
            return true
        }
    }

    companion object {
        private object SearchesInProgress : ThreadLocal<HashSet<KtDeclaration>>() {
            override fun initialValue() = HashSet<KtDeclaration>()
        }
    }

/*
    constructor(method: PsiMethod)
    fun run() {

        if (invokeFunction !is KtLightMethod) return //TODO
        val ktDeclarationTarget = invokeFunction.kotlinOrigin as? KtDeclaration ?: return //TODO?
        findInvokeOperatorUsages(ktDeclarationTarget, scope, consumer)
    }
*/

    open fun run() {
        val inProgress = SearchesInProgress.get()
        try {
            if (!inProgress.add(targetDeclaration)) return //TODO: it's not quite correct

            val usePlainSearch = when (ExpressionsOfTypeProcessor.mode) {
                ExpressionsOfTypeProcessor.Mode.ALWAYS_SMART -> false
                ExpressionsOfTypeProcessor.Mode.ALWAYS_PLAIN -> true
                ExpressionsOfTypeProcessor.Mode.PLAIN_WHEN_NEEDED -> searchScope is LocalSearchScope // for local scope it's faster to use plain search
            }
            if (usePlainSearch) {
                doPlainSearch(searchScope)
                return
            }

            val descriptor = targetDeclaration.resolveToDescriptor() as? CallableDescriptor ?: return
            if (descriptor is FunctionDescriptor && !descriptor.isValidOperator()) return

            val dataType = if (descriptor.isExtension) {
                descriptor.fuzzyExtensionReceiverType()!!
            }
            else {
                val classDescriptor = descriptor.containingDeclaration as? ClassDescriptor ?: return
                classDescriptor.defaultType.toFuzzyType(classDescriptor.typeConstructor.parameters)
            }

            ExpressionsOfTypeProcessor(
                    dataType,
                    searchScope,
                    suspiciousExpressionHandler = { expression -> processSuspiciousExpression(expression) },
                    suspiciousScopeHandler = { searchScope -> doPlainSearch(searchScope) },
                    resolutionFacade = targetDeclaration.getResolutionFacade()
            ).run()
        }
        finally {
            inProgress.remove(targetDeclaration)
        }
    }

    private fun doPlainSearch(scope: SearchScope) {
        if (scope is LocalSearchScope) {
            for (element in scope.scope) {
                element.accept(object : PsiRecursiveElementWalkingVisitor() {
                    override fun visitElement(element: PsiElement) {
                        if (isReferenceElement(element)) {
                            processReferenceElement(element)
                        }

                        super.visitElement(element)
                    }
                })
            }
        }
        else {
            scope as GlobalSearchScope
            if (wordToSearch != null) {
                val unwrappedElement = targetDeclaration.namedUnwrappedElement ?: return
                val resultProcessor = KotlinRequestResultProcessor(unwrappedElement,
                                                                   filter = { ref -> isOurReference(ref) })
                optimizer.searchWord(wordToSearch, scope.restrictToKotlinSources(), UsageSearchContext.IN_CODE, true, unwrappedElement, resultProcessor)
            }
            else {
                val psiManager = PsiManager.getInstance(project)
                ProjectRootManager.getInstance(project).fileIndex.iterateContent { file ->
                    if (file in scope) {
                        val ktFile = psiManager.findFile(file) as? KtFile
                        if (ktFile != null) {
                            doPlainSearch(LocalSearchScope(ktFile))
                        }
                    }
                    true
                }
            }
        }
    }

    private fun isOurReference(ref: PsiReference) = referenceType.java.isAssignableFrom(ref.javaClass)
}

class InvokeOperatorReferenceSearcher(
        targetDeclaration: KtDeclaration,
        searchScope: SearchScope,
        consumer: Processor<PsiReference>,
        optimizer: SearchRequestCollector
) : OperatorReferenceSearcher(targetDeclaration, searchScope, consumer, optimizer, KtInvokeFunctionReference::class, wordToSearch = null) {

    companion object {
        fun runForPsiMethod(
                invokeFunction: PsiMethod,
                scope: SearchScope,
                consumer: Processor<PsiReference>,
                optimizer: SearchRequestCollector
        ) {
            if (invokeFunction !is KtLightMethod) return //TODO
            val ktDeclarationTarget = invokeFunction.kotlinOrigin as? KtDeclaration ?: return //TODO?
            InvokeOperatorReferenceSearcher(ktDeclarationTarget, scope, consumer, optimizer).run()
        }
    }

    override fun processSuspiciousExpression(expression: KtExpression) {
        val callExpression = expression.parent as? KtCallExpression ?: return
        testLog?.add("Resolving call ${callExpression.text}")
        processReferenceElement(callExpression)
    }

    override fun isReferenceElement(element: PsiElement) = element is KtCallExpression
}

class DestructuringDeclarationReferenceSearcher(
        targetDeclaration: KtDeclaration,
        searchScope: SearchScope,
        consumer: Processor<PsiReference>,
        optimizer: SearchRequestCollector
) : OperatorReferenceSearcher(targetDeclaration, searchScope, consumer, optimizer, KtDestructuringDeclarationReference::class, wordToSearch = "(") {

    companion object {
        fun runForPsiMethod(
                componentFunction: PsiMethod,
                scope: SearchScope,
                consumer: Processor<PsiReference>,
                optimizer: SearchRequestCollector
        ) {
            if (componentFunction !is KtLightMethod) return //TODO
            val ktDeclarationTarget = componentFunction.kotlinOrigin as? KtDeclaration ?: return //TODO?
            DestructuringDeclarationReferenceSearcher(ktDeclarationTarget, scope, consumer, optimizer).run()
        }
    }

    private val componentIndex = when (targetDeclaration) {
        is KtParameter -> targetDeclaration.dataClassComponentFunction()?.name?.asString()?.let { getComponentIndex(it) }
        is KtFunction -> targetDeclaration.name?.let { getComponentIndex(it) }
    //TODO: java component functions (see KT-13605)
        else -> null
    }

    override fun run() {
        if (componentIndex == null) return
        super.run()
    }

    override fun isReferenceElement(element: PsiElement): Boolean {
        return element is KtDestructuringDeclarationEntry
               && (element.parent as KtDestructuringDeclaration).entries.indexOf(element) == componentIndex!! - 1
    }

    override fun processSuspiciousExpression(expression: KtExpression) {
        val parent = expression.parent
        val destructuringDeclaration = when (parent) {
            is KtDestructuringDeclaration -> parent

            is KtContainerNode -> {
                if (parent.node.elementType == KtNodeTypes.LOOP_RANGE) {
                    (parent.parent as KtForExpression).destructuringParameter
                }
                else {
                    null
                }
            }

            else -> null
        }

        if (destructuringDeclaration != null && componentIndex!! <= destructuringDeclaration.entries.size) {
            testLog?.add("Checked type of ${ExpressionsOfTypeProcessor.logPresentation(destructuringDeclaration)}")
            processReferenceElement(destructuringDeclaration.entries[componentIndex - 1])
        }
    }
}