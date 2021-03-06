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

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.lang.java.JavaLanguage
import com.intellij.lang.xml.XMLLanguage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.psi.*
import com.intellij.psi.search.*
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.util.Processor
import org.jetbrains.kotlin.KtNodeTypes
import org.jetbrains.kotlin.asJava.classes.KtLightClass
import org.jetbrains.kotlin.asJava.elements.KtLightMethod
import org.jetbrains.kotlin.asJava.namedUnwrappedElement
import org.jetbrains.kotlin.asJava.toLightClass
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.diagnostics.DiagnosticUtils
import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.resolveToDescriptorIfAny
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.refactoring.fqName.getKotlinFqName
import org.jetbrains.kotlin.idea.references.KtDestructuringDeclarationReference
import org.jetbrains.kotlin.idea.search.ideaExtensions.KotlinReferencesSearchOptions
import org.jetbrains.kotlin.idea.search.ideaExtensions.KotlinReferencesSearchParameters
import org.jetbrains.kotlin.idea.search.ideaExtensions.KotlinRequestResultProcessor
import org.jetbrains.kotlin.idea.search.restrictToKotlinSources
import org.jetbrains.kotlin.idea.search.usagesSearch.DestructuringDeclarationUsageSearch.*
import org.jetbrains.kotlin.idea.util.FuzzyType
import org.jetbrains.kotlin.idea.util.ProjectRootsUtil
import org.jetbrains.kotlin.idea.util.fuzzyExtensionReceiverType
import org.jetbrains.kotlin.idea.util.toFuzzyType
import org.jetbrains.kotlin.kdoc.psi.impl.KDocName
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.sam.SingleAbstractMethodUtils
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.*
import org.jetbrains.kotlin.resolve.dataClassUtils.getComponentIndex
import org.jetbrains.kotlin.resolve.descriptorUtil.isExtension
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.util.isValidOperator
import org.jetbrains.kotlin.utils.addToStdlib.firstIsInstance
import java.util.*

enum class DestructuringDeclarationUsageSearch {
    ALWAYS_SMART,
    ALWAYS_PLAIN,
    PLAIN_WHEN_NEEDED // use plain search for LocalSearchScope and when unknown type of reference encountered
}

// for tests
var destructuringDeclarationUsageSearchMode = if (ApplicationManager.getApplication().isUnitTestMode) ALWAYS_SMART else PLAIN_WHEN_NEEDED
var destructuringDeclarationUsageSearchLog: MutableList<String>? = null

private object DestructuringDeclarationSearchesInProgress : ThreadLocal<HashSet<KtDeclaration>>() {
    override fun initialValue() = HashSet<KtDeclaration>()
}

//TODO: check if it's too expensive

fun findDestructuringDeclarationUsages(
        componentFunction: PsiMethod,
        scope: SearchScope,
        consumer: Processor<PsiReference>,
        optimizer: SearchRequestCollector
) {
    if (componentFunction !is KtLightMethod) return //TODO
    val ktDeclarationTarget = componentFunction.kotlinOrigin as? KtDeclaration ?: return //TODO?
    findDestructuringDeclarationUsages(ktDeclarationTarget, scope, consumer, optimizer)
}

fun findDestructuringDeclarationUsages(
        ktDeclaration: KtDeclaration,
        scope: SearchScope,
        consumer: Processor<PsiReference>,
        optimizer: SearchRequestCollector
) {
    val inProgress = DestructuringDeclarationSearchesInProgress.get()
    try {
        if (!inProgress.add(ktDeclaration)) return

        val usePlainSearch = when (destructuringDeclarationUsageSearchMode) {
            ALWAYS_SMART -> false
            ALWAYS_PLAIN -> true
            PLAIN_WHEN_NEEDED -> scope is LocalSearchScope // for local scope it's faster to use plain search
        }
        if (usePlainSearch) {
            doPlainSearch(ktDeclaration, scope, optimizer)
            return
        }

        val descriptor = ktDeclaration.resolveToDescriptor() as? CallableDescriptor ?: return

        if (descriptor is FunctionDescriptor && !descriptor.isValidOperator()) return

        val dataType = if (descriptor.isExtension) {
            descriptor.fuzzyExtensionReceiverType()!!
        }
        else {
            val classDescriptor = descriptor.containingDeclaration as? ClassDescriptor ?: return
            classDescriptor.defaultType.toFuzzyType(classDescriptor.typeConstructor.parameters)
        }

        val componentIndex = when (ktDeclaration) {
            is KtParameter -> ktDeclaration.dataClassComponentFunction()?.name?.asString()?.let { getComponentIndex(it) }
            is KtFunction -> ktDeclaration.name?.let { getComponentIndex(it) }
        //TODO: java component functions (see KT-13605)
            else -> null
        } ?: return

        Processor(dataType,
                  ktDeclaration,
                  componentIndex,
                  scope,
                  consumer,
                  plainSearchHandler = { searchScope -> doPlainSearch(ktDeclaration, searchScope, optimizer) },
                  testLog = destructuringDeclarationUsageSearchLog
        ).run()
    }
    finally {
        inProgress.remove(ktDeclaration)
    }
}

private fun doPlainSearch(ktDeclaration: KtDeclaration, scope: SearchScope, optimizer: SearchRequestCollector) {
    val unwrappedElement = ktDeclaration.namedUnwrappedElement ?: return
    val resultProcessor = KotlinRequestResultProcessor(unwrappedElement,
                                                       filter = { ref -> ref is KtDestructuringDeclarationReference })
    optimizer.searchWord("(", scope.restrictToKotlinSources(), UsageSearchContext.IN_CODE, true, unwrappedElement, resultProcessor)
}

private class Processor(
        private val dataType: FuzzyType,
        private val target: KtDeclaration,
        private val componentIndex: Int,
        private val searchScope: SearchScope,
        private val consumer: Processor<PsiReference>,
        plainSearchHandler: (SearchScope) -> Unit,
        private val testLog: MutableList<String>?
) {
    private val plainSearchHandler: (SearchScope) -> Unit = { scope ->
        testLog?.add("Used plain search in ${scope.logPresentation()}")
        plainSearchHandler(scope)
    }

    private val project = target.project

    // note: a Task must define equals & hashCode!
    private interface Task {
        fun perform()
    }

    private val tasks = ArrayDeque<Task>()
    private val taskSet = HashSet<Any>()

    private val scopesToUsePlainSearch = LinkedHashMap<KtFile, ArrayList<PsiElement>>()

    fun run() {
        val dataClassDescriptor = dataType.type.constructor.declarationDescriptor ?: return
        val dataClassDeclaration = DescriptorToSourceUtilsIde.getAnyDeclaration(project, dataClassDescriptor)
        val psiClass = when (dataClassDeclaration) {
            is PsiClass -> dataClassDeclaration
            is KtClassOrObject -> dataClassDeclaration.toLightClass() ?: return
            else -> return
        }

        // for data class from library always use plain search because we cannot search usages in compiled code (we could though)
        if (!ProjectRootsUtil.isInProjectSource(psiClass)) {
            plainSearchHandler(searchScope)
            return
        }

        addClassToProcess(psiClass)

        processTasks()

        val scopeElements = scopesToUsePlainSearch.values.flatMap { it }.toTypedArray()
        plainSearchHandler(LocalSearchScope(scopeElements))
    }

    private fun addTask(task: Task) {
        if (taskSet.add(task)) {
            tasks.push(task)
        }

    }

    private fun processTasks() {
        while (tasks.isNotEmpty()) {
            tasks.pop().perform()
        }
    }

    private fun downShiftToPlainSearch() {
        tasks.clear()
        scopesToUsePlainSearch.clear()
        plainSearchHandler(searchScope)
    }

    private fun addClassToProcess(classToSearch: PsiClass) {
        data class ProcessClassUsagesTask(val classToSearch: PsiClass) : Task {
            override fun perform() {
                testLog?.add("Searched references to ${classToSearch.logPresentation()}")
                ReferencesSearch.search(classToSearch).forEach(Processor processor@ { reference -> //TODO: see KT-13607
                    if (processDataClassUsage(reference)) return@processor true

                    if (destructuringDeclarationUsageSearchMode != ALWAYS_SMART) {
                        downShiftToPlainSearch()
                        return@processor false
                    }

                    val element = reference.element
                    val document = PsiDocumentManager.getInstance(project).getDocument(element.containingFile)
                    val lineAndCol = DiagnosticUtils.offsetToLineAndColumn(document, element.startOffset)
                    error("Unsupported reference: '${element.text}' in ${element.containingFile.name} line ${lineAndCol.line} column ${lineAndCol.column}")
                })

                // we must use plain search inside our data class (and inheritors) because implicit 'this' can happen anywhere
                (classToSearch as? KtLightClass)?.kotlinOrigin?.let { usePlainSearch(it) }
            }
        }
        addTask(ProcessClassUsagesTask(classToSearch))
    }

    private enum class CallableToProcessKind {
        HAS_DATA_CLASS_TYPE,
        PROCESS_LAMBDAS
    }

    /**
     * Adds declaration whose type is our data class (or data class used anywhere inside that type)
     * or which has parameter of functional type with our data class used inside
     */
    private fun addCallableDeclarationToProcess(declaration: PsiElement, kind: CallableToProcessKind) {
        if (declaration.isOperatorExpensiveToSearch()) { // cancel all tasks and use plain search
            downShiftToPlainSearch()
            return
        }

        data class ProcessCallableUsagesTask(val declaration: PsiElement, val kind: CallableToProcessKind) : Task {
            override fun perform() {
                // we don't need to search usages of declarations in Java because Java doesn't have implicitly typed declarations so such usages cannot affect Kotlin code
                //TODO: what about Scala and other JVM-languages?
                val scope = GlobalSearchScope.projectScope(project).restrictToKotlinSources()
                testLog?.add("Searched references to ${declaration.logPresentation()} in Kotlin files")
                val searchParameters = KotlinReferencesSearchParameters(
                        declaration, scope, kotlinOptions = KotlinReferencesSearchOptions(searchNamedArguments = false))
                ReferencesSearch.search(searchParameters).forEach { reference ->
                    when (kind) {
                        CallableToProcessKind.HAS_DATA_CLASS_TYPE -> {
                            if (reference is KtDestructuringDeclarationReference) {
                                // declaration usage in form of destructuring declaration entry
                                addCallableDeclarationToProcess(reference.element, CallableToProcessKind.HAS_DATA_CLASS_TYPE)
                            }
                            else {
                                (reference.element as? KtReferenceExpression)?.let { processSuspiciousExpression(it) }
                            }
                        }

                        CallableToProcessKind.PROCESS_LAMBDAS -> {
                            (reference.element as? KtReferenceExpression)?.let { processLambdasForCallableReference(it) }
                        }
                    }
                }
            }
        }
        addTask(ProcessCallableUsagesTask(declaration, kind))
    }

    private fun addSamInterfaceToProcess(psiClass: PsiClass) {
        data class ProcessSamInterfaceTask(val psiClass: PsiClass) : Task {
            override fun perform() {
                //TODO: what about other JVM languages?
                val scope = GlobalSearchScope.getScopeRestrictedByFileTypes(GlobalSearchScope.projectScope(project), JavaFileType.INSTANCE)
                testLog?.add("Searched references to ${psiClass.logPresentation()} in java files")
                ReferencesSearch.search(psiClass, scope).forEach { reference ->
                    // check if the reference is method parameter type
                    val parameter = ((reference as? PsiJavaCodeReferenceElement)?.parent as? PsiTypeElement)?.parent as? PsiParameter
                    val method = parameter?.declarationScope as? PsiMethod
                    if (method != null) {
                        addCallableDeclarationToProcess(method, CallableToProcessKind.PROCESS_LAMBDAS)
                    }
                }
            }
        }
        addTask(ProcessSamInterfaceTask(psiClass))
    }

    /**
     * Process usage of our data class or one of its inheritors
     */
    private fun processDataClassUsage(reference: PsiReference): Boolean {
        val element = reference.element
        return when (element.language) {
            KotlinLanguage.INSTANCE -> processKotlinDataClassUsage(element)

            JavaLanguage.INSTANCE -> processJavaDataClassUsage(element)

            XMLLanguage.INSTANCE -> true // ignore usages in XML - they don't affect us

            else -> false // we don't know anything about usages in other languages - so we downgrade to slow algorithm in this case
        }
    }

    private fun processKotlinDataClassUsage(element: PsiElement): Boolean {
        //TODO: type aliases

        when (element) {
            is KtReferenceExpression -> {
                val parent = element.parent
                when (parent) {
                    is KtUserType -> {
                        return processDataClassUsageInUserType(parent)
                    }

                    is KtCallExpression -> {
                        if (element == parent.calleeExpression) {
                            processSuspiciousExpression(parent)
                            return true
                        }
                    }

                    is KtContainerNode -> {
                        if (parent.node.elementType == KtNodeTypes.LABEL_QUALIFIER) {
                            return true // this@ClassName - it will be handled anyway because members and extensions are processed with plain search
                        }
                    }

                    is KtQualifiedExpression -> {
                        if (element == parent.receiverExpression) {
                            return true // companion object member or static member access - ignore it
                        }
                    }

                    is KtCallableReferenceExpression -> {
                        when (element) {
                            parent.receiverExpression -> { // usage in receiver of callable reference (before "::") - ignore it
                                return true
                            }

                            parent.callableReference -> { // usage after "::" in callable reference - should be reference to constructor of our data class
                                processSuspiciousExpression(element)
                                return true
                            }
                        }
                    }

                    is KtClassLiteralExpression -> {
                        if (element == parent.receiverExpression) { // ClassName::class
                            processSuspiciousExpression(element)
                            return true
                        }
                    }
                }

                if (element.getStrictParentOfType<KtImportDirective>() != null) return true // ignore usage in import
            }

            is KDocName -> return true // ignore usage in doc-comment
        }

        return false // unsupported type of reference
    }

    private fun processDataClassUsageInUserType(userType: KtUserType): Boolean {
        val typeRef = userType.parents.lastOrNull { it is KtTypeReference }
        val typeRefParent = typeRef?.parent
        when (typeRefParent) {
            is KtCallableDeclaration -> {
                when (typeRef) {
                    typeRefParent.typeReference -> {
                        addCallableDeclarationToProcess(typeRefParent, CallableToProcessKind.HAS_DATA_CLASS_TYPE)

                        if (typeRefParent is KtParameter) { //TODO: what if functional type is declared with "FunctionN<...>"?
                            val usedInsideFunctionalType = userType.parents.takeWhile { it != typeRef }.any { it is KtFunctionType }
                            if (usedInsideFunctionalType) {
                                val function = (typeRefParent.parent as? KtParameterList)?.parent as? KtFunction
                                if (function != null) {
                                    addCallableDeclarationToProcess(function, CallableToProcessKind.PROCESS_LAMBDAS)
                                }
                            }
                        }

                        return true
                    }

                    typeRefParent.receiverTypeReference -> {
                        // we must use plain search inside extensions because implicit 'this' can happen anywhere
                        usePlainSearch(typeRefParent)
                        return true
                    }
                }
            }

            is KtTypeProjection -> {
                val callExpression = (typeRefParent.parent as? KtTypeArgumentList)?.parent as? KtCallExpression
                if (callExpression != null) {
                    processSuspiciousExpression(callExpression)
                    return true
                }
            }

            is KtConstructorCalleeExpression -> {
                val parent = typeRefParent.parent
                if (parent is KtSuperTypeCallEntry) {
                    val classOrObject = (parent.parent as KtSuperTypeList).parent as KtClassOrObject
                    val psiClass = classOrObject.toLightClass()
                    psiClass?.let { addClassToProcess(it) }
                    return true
                }
            }

            is KtSuperTypeListEntry -> {
                if (typeRef == typeRefParent.typeReference) {
                    val classOrObject = (typeRefParent.parent as KtSuperTypeList).parent as KtClassOrObject
                    val psiClass = classOrObject.toLightClass()
                    psiClass?.let { addClassToProcess(it) }
                    return true
                }
            }

            is KtIsExpression -> {
                val scopeOfPossibleSmartCast = typeRefParent.getParentOfType<KtDeclarationWithBody>(true)
                scopeOfPossibleSmartCast?.let { usePlainSearch(it) }
                return true
            }

            is KtWhenConditionIsPattern -> {
                val whenEntry = typeRefParent.parent as KtWhenEntry
                if (typeRefParent.isNegated) {
                    val whenExpression = whenEntry.parent as KtWhenExpression
                    val entriesAfter = whenExpression.entries.dropWhile { it != whenEntry }.drop(1)
                    entriesAfter.forEach { usePlainSearch(it) }
                }
                else {
                    usePlainSearch(whenEntry)
                }
                return true
            }

            is KtBinaryExpressionWithTypeRHS -> {
                processSuspiciousExpression(typeRefParent)
                return true
            }
        }

        return false // unsupported case
    }

    private fun processJavaDataClassUsage(element: PsiElement): Boolean {
        if (element !is PsiJavaCodeReferenceElement) return true // meaningless reference from Java

        var prev = element
        ParentsLoop@
        for (parent in element.parents) {
            when (parent) {
                is PsiCodeBlock,
                is PsiExpression ->
                    break@ParentsLoop // ignore local usages

                is PsiMethod -> {
                    if (prev == parent.returnTypeElement && !parent.isPrivateOrLocal()) {
                        addCallableDeclarationToProcess(parent, CallableToProcessKind.HAS_DATA_CLASS_TYPE)
                    }
                    break@ParentsLoop
                }

                is PsiField -> {
                    if (prev == parent.typeElement && !parent.isPrivateOrLocal()) {
                        addCallableDeclarationToProcess(parent, CallableToProcessKind.HAS_DATA_CLASS_TYPE)
                    }
                    break@ParentsLoop
                }

                is PsiReferenceList -> {
                    if (parent.role == PsiReferenceList.Role.EXTENDS_LIST || parent.role == PsiReferenceList.Role.IMPLEMENTS_LIST) {
                        val psiClass = parent.parent as PsiClass
                        if (!psiClass.isPrivateOrLocal()) {
                            addClassToProcess(psiClass)
                        }
                    }
                    break@ParentsLoop
                }

                //TODO: if Java parameter has Kotlin functional type then we should process method usages
                is PsiParameter -> {
                    if (prev == parent.typeElement) {
                        val method = parent.declarationScope as? PsiMethod
                        if (method != null && method.hasModifierProperty(PsiModifier.ABSTRACT)) {
                            val psiClass = method.containingClass
                            if (psiClass != null) {
                                testLog?.add("Resolved java class to descriptor: ${psiClass.qualifiedName}")

                                val classDescriptor = psiClass.resolveToDescriptor(target.getResolutionFacade())
                                if (classDescriptor != null && SingleAbstractMethodUtils.getSingleAbstractMethodOrNull(classDescriptor) != null) {
                                    addSamInterfaceToProcess(psiClass)
                                }
                            }
                        }
                    }
                    break@ParentsLoop
                }
            }

            prev = parent
        }

        return true
    }

    /**
     * Process expression which may have type of our data class (or data class used anywhere inside that type)
     */
    private fun processSuspiciousExpression(expression: KtExpression) {
        var affectedScope: PsiElement = expression
        ParentsLoop@
        for (element in expression.parentsWithSelf) {
            affectedScope = element
            if (element !is KtExpression) continue

            val parent = element.parent
            when (parent) {
                is KtDestructuringDeclaration -> {
                    processSuspiciousDeclaration(parent)
                    break@ParentsLoop
                }

                is KtWithExpressionInitializer -> {
                    if (element == parent.initializer) {
                        processSuspiciousDeclaration(parent)
                    }
                    break@ParentsLoop
                }

                is KtContainerNode -> {
                    if (parent.node.elementType == KtNodeTypes.LOOP_RANGE) {
                        val forExpression = parent.parent as KtForExpression
                        (forExpression.destructuringParameter ?: forExpression.loopParameter as KtDeclaration?)?.let {
                            processSuspiciousDeclaration(it)
                        }
                        break@ParentsLoop
                    }
                }
            }

            if (!element.mayTypeAffectAncestors()) break
        }

        // use plain search in all lambdas and anonymous functions inside because they parameters or receiver can be implicitly typed with our data class
        usePlainSearchInLambdas(affectedScope)
    }

    private fun processLambdasForCallableReference(expression: KtReferenceExpression) {
        //TODO: receiver?
        usePlainSearchInLambdas(expression.parent)
    }

    /**
     * Process declaration which may have implicit type of our data class (or data class used anywhere inside that type)
     */
    private fun processSuspiciousDeclaration(declaration: KtDeclaration) {
        if (declaration is KtDestructuringDeclaration) {
            if (searchScope.contains(declaration) && componentIndex <= declaration.entries.size) {
                testLog?.add("Checked type of ${declaration.logPresentation()}")

                val declarationReference = declaration.entries[componentIndex - 1].references.firstIsInstance<KtDestructuringDeclarationReference>()
                if (declarationReference.isReferenceTo(target)) {
                    consumer.process(declarationReference)
                }
            }
        }
        else {
            if (!isImplicitlyTyped(declaration)) return

            testLog?.add("Checked type of ${declaration.logPresentation()}")

            val descriptor = declaration.resolveToDescriptorIfAny() as? CallableDescriptor ?: return
            val type = descriptor.returnType
            if (type != null && type.containsTypeOrDerivedInside(dataType)) {
                addCallableDeclarationToProcess(declaration, CallableToProcessKind.HAS_DATA_CLASS_TYPE)
            }
        }
    }

    private fun usePlainSearchInLambdas(scope: PsiElement) {
        scope.forEachDescendantOfType<KtFunction> {
            if (it.nameIdentifier == null) {
                usePlainSearch(it)
            }
        }
    }

    private fun usePlainSearch(scope: KtElement) {
        val file = scope.getContainingKtFile()
        val restricted = LocalSearchScope(scope).intersectWith(searchScope) as LocalSearchScope
        ScopeLoop@
        for (element in restricted.scope) {
            val prevElements = scopesToUsePlainSearch.getOrPut(file) { ArrayList() }
            for ((index, prevElement) in prevElements.withIndex()) {
                if (prevElement.isAncestor(element, strict = false)) continue@ScopeLoop
                if (element.isAncestor(prevElement)) {
                    prevElements[index] = element
                    continue@ScopeLoop
                }
            }
            prevElements.add(element)
        }
    }

    //TODO: code is quite similar to PartialBodyResolveFilter.isValueNeeded
    private fun KtExpression.mayTypeAffectAncestors(): Boolean {
        val parent = this.parent
        when (parent) {
            is KtBlockExpression -> {
                return this == parent.statements.last() && parent.mayTypeAffectAncestors()
            }

            is KtDeclarationWithBody -> {
                if (this == parent.bodyExpression) {
                    return !parent.hasBlockBody() && !parent.hasDeclaredReturnType()
                }
            }

            is KtContainerNode -> {
                val grandParent = parent.parent
                return when (parent.node.elementType) {
                    KtNodeTypes.CONDITION, KtNodeTypes.BODY -> false
                    KtNodeTypes.THEN, KtNodeTypes.ELSE -> (grandParent as KtExpression).mayTypeAffectAncestors()
                    KtNodeTypes.LOOP_RANGE, KtNodeTypes.INDICES -> true
                    else -> true // something else unknown
                }
            }
        }
        return true // we don't know
    }

    private fun PsiModifierListOwner.isPrivateOrLocal(): Boolean {
        return hasModifierProperty(PsiModifier.PRIVATE) || parents.any { it is PsiCodeBlock }
    }

    private fun PsiElement.isOperatorExpensiveToSearch(): Boolean {
        when (this) {
            is KtFunction -> {
                if (name?.startsWith("component") == true) return false // component functions are not so expensive to search
                return  hasModifier(KtTokens.OPERATOR_KEYWORD)
                        || hasModifier(KtTokens.OVERRIDE_KEYWORD) && (resolveToDescriptorIfAny() as? FunctionDescriptor)?.isOperator == true
            }

            is KtLightMethod -> {
                return kotlinOrigin?.isOperatorExpensiveToSearch() == true
            }

            else -> {
                return false
            }
        }
    }

    private fun KotlinType.containsTypeOrDerivedInside(type: FuzzyType): Boolean {
        return type.checkIsSuperTypeOf(this) != null || arguments.any { it.type.containsTypeOrDerivedInside(type) }
    }

    private fun isImplicitlyTyped(declaration: KtDeclaration): Boolean {
        return when (declaration) {
            is KtFunction -> !declaration.hasDeclaredReturnType()
            is KtVariableDeclaration -> declaration.typeReference == null
            is KtParameter -> declaration.typeReference == null
            else -> false
        }
    }

    private fun PsiElement.logPresentation(): String? {
        val fqName = getKotlinFqName()?.asString()
                     ?: (this as? KtNamedDeclaration)?.name
        return when (this) {
            is PsiMethod, is KtFunction -> fqName + "()"
            is KtParameter -> {
                val owner = this.ownerFunction?.logPresentation() ?: this.parent.toString()
                "parameter ${this.name} in $owner"
            }
            is KtDestructuringDeclaration -> entries.joinToString(", ", prefix = "(", postfix = ")") { it.text }
            else -> fqName
        }
    }

    private fun SearchScope.logPresentation(): String {
        return when (this) {
            searchScope -> "whole search scope"

            is LocalSearchScope -> {
                scope
                        .map { element ->
                            "    " + when (element) {
                                is KtFunctionLiteral -> element.text
                                is KtWhenEntry -> {
                                    if (element.isElse)
                                        "KtWhenEntry \"else\""
                                    else
                                        "KtWhenEntry \"" + element.conditions.joinToString(", ") { it.text } + "\""
                                }
                                is KtNamedDeclaration -> element.node.elementType.toString() + ":" + element.name
                                else -> element.toString()
                            }
                        }
                        .toList()
                        .sorted()
                        .joinToString("\n", "LocalSearchScope:\n")
            }

            else -> this.displayName
        }

    }
}
