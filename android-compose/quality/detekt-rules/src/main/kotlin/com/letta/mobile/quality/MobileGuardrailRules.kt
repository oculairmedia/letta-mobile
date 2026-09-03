package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Debt
import io.gitlab.arturbosch.detekt.api.Entity
import io.gitlab.arturbosch.detekt.api.Issue
import io.gitlab.arturbosch.detekt.api.Rule
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider
import io.gitlab.arturbosch.detekt.api.Severity
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.KtThrowExpression
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.KtTypeReference
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

private fun KtFile.normalizedPath(): String = virtualFilePath.replace('\\', '/')
private fun KtFile.isTestSource(): Boolean = normalizedPath().contains("/src/") &&
    normalizedPath().substringAfter("/src/").substringBefore('/').contains("test", ignoreCase = true)
private fun KtFile.isGenerated(): Boolean = normalizedPath().let {
    "/generated/" in it || "/build/" in it || "/ksp/" in it
}

internal abstract class MobileRule(config: Config, name: String, description: String) : Rule(config) {
    final override val issue = Issue(name, Severity.Defect, description, Debt.TWENTY_MINS)

    protected fun report(element: KtElement, message: String) {
        report(CodeSmell(issue, Entity.from(element), message))
    }
}

internal class NoAnyType(config: Config = Config.empty) : MobileRule(
    config,
    "NoAnyType",
    "Production boundaries must use typed models or adapters rather than Any.",
) {
    private var anyTypeAliases = emptySet<String>()

    override fun visitKtFile(file: KtFile) {
        if (file.isTestSource() || file.isGenerated()) return
        anyTypeAliases = file.collectDescendantsOfType<KtTypeAlias>()
            .filter { it.getTypeReference()?.containsAnyType() == true }
            .mapNotNull { it.name }
            .toSet()
        super.visitKtFile(file)
        anyTypeAliases = emptySet()
    }

    override fun visitTypeReference(typeReference: KtTypeReference) {
        super.visitTypeReference(typeReference)
        val bannedAlias = typeReference.collectTypeNames().firstOrNull(anyTypeAliases::contains)
        if (typeReference.containsAnyType() || bannedAlias != null) {
            report(typeReference, "Replace '${typeReference.text}' with a typed model or boundary adapter.")
        }
    }

    private fun KtTypeReference.containsAnyType(): Boolean =
        collectTypeNames().any { it == "Any" || it == "kotlin.Any" }
    private fun KtTypeReference.collectTypeNames(): List<String> = text
        .split('<', '>', ',', '?', '(', ')', '[', ']', ' ', '\n', '\t')
        .map(String::trim)
        .filter(String::isNotEmpty)
}

internal class NoSelectStarInRoomDao(config: Config = Config.empty) : MobileRule(
    config,
    "NoSelectStarInRoomDao",
    "Room queries must enumerate columns so schema changes cannot silently widen mappings.",
) {
    override fun visitAnnotationEntry(annotationEntry: KtAnnotationEntry) {
        super.visitAnnotationEntry(annotationEntry)
        if (annotationEntry.shortName?.asString() != "Query") return
        val sql = annotationEntry.valueArguments
            .mapNotNull { it.getArgumentExpression() as? KtStringTemplateExpression }
            .joinToString(" ") { it.entries.joinToString("") { entry -> entry.text } }
        val tokens = sql.uppercase().replace(',', ' ').replace('(', ' ').replace(')', ' ')
            .split(Regex("\\s+")).filter(String::isNotBlank)
        if (tokens.zipWithNext().any { (left, right) -> left == "SELECT" && right == "*" }) {
            report(annotationEntry, "Enumerate the columns selected by this Room @Query.")
        }
    }
}

internal class NoProcessGlobalMutableState(config: Config = Config.empty) : MobileRule(
    config,
    "NoProcessGlobalMutableState",
    "Process-global mutable collections and state hide ownership and leak across lifecycles.",
) {
    private val mutableFactories = setOf(
        "mutableMapOf", "hashMapOf", "linkedMapOf", "mutableListOf", "arrayListOf", "mutableSetOf", "hashSetOf",
        "MutableStateFlow", "mutableStateOf", "mutableStateListOf", "mutableStateMapOf",
    )

    override fun visitProperty(property: KtProperty) {
        super.visitProperty(property)
        val isViolation = listOf(
            !property.containingKtFile.isIgnoredSource(),
            property.hasProcessGlobalOwner(),
            !property.hasModifier(KtTokens.CONST_KEYWORD),
            property.holdsMutableState(),
        ).all { it }
        if (isViolation) report(property, "Move mutable state into an injected, lifecycle-owned instance.")
    }

    private fun KtFile.isIgnoredSource(): Boolean {
        if (isTestSource()) return true
        return isGenerated()
    }

    private fun KtProperty.hasProcessGlobalOwner(): Boolean = when (val owner = parent) {
        is KtFile -> true
        is KtClassBody -> owner.parent is KtObjectDeclaration
        else -> false
    }

    private fun KtProperty.holdsMutableState(): Boolean {
        val factory = (initializer as? KtCallExpression)?.calleeExpression?.text
        val type = typeReference?.text.orEmpty()
        val mutableVariableCollection = if (isVar) collectionTypes.any(type::contains) else false
        val mutableSignals = listOf(
            factory in mutableFactories,
            mutableTypes.any(type::contains),
            mutableVariableCollection,
        )
        return mutableSignals.any { it }
    }

    private companion object {
        val mutableTypes = listOf(
            "MutableMap", "MutableList", "MutableSet", "MutableCollection", "MutableState", "MutableStateFlow",
        )
        val collectionTypes = listOf("Map", "List", "Set", "Collection", "StateFlow", "State")
    }
}

internal class CancellationMustPropagate(config: Config = Config.empty) : MobileRule(
    config,
    "CancellationMustPropagate",
    "Coroutine code catching Exception or Throwable must explicitly propagate cancellation.",
) {
    private val coroutineBuilders = setOf(
        "launch", "async", "runBlocking", "runTest", "withContext", "coroutineScope", "supervisorScope",
    )

    override fun visitCatchSection(catchClause: KtCatchClause) {
        super.visitCatchSection(catchClause)
        val caught = catchClause.caughtType()
        val parameter = catchClause.catchParameter?.name
        val body = catchClause.catchBody
        val isViolation = listOf(
            caught in genericExceptionTypes,
            parameter != null,
            body != null,
            isCoroutineContext(catchClause),
            body?.propagatesCancellation(parameter.orEmpty()) != true,
            !cancellationHandledByEarlierCatch(catchClause),
        ).all { it }
        if (isViolation) {
            report(catchClause, "Rethrow CancellationException before handling $caught.")
        }
    }

    private fun KtCatchClause.caughtType(): String? =
        catchParameter?.typeReference?.text?.removePrefix("kotlin.")

    private fun KtElement.propagatesCancellation(parameter: String): Boolean =
        collectDescendantsOfType<KtThrowExpression>().any { it.propagatesCancellation(parameter) }

    private fun KtThrowExpression.propagatesCancellation(parameter: String): Boolean {
        val expression = thrownExpression?.text.orEmpty()
        if (expression == parameter) return true
        return "CancellationException" in expression
    }

    private fun cancellationHandledByEarlierCatch(catchClause: KtCatchClause): Boolean {
        val tryExpression = catchClause.parent as? KtTryExpression ?: return false
        return tryExpression.catchClauses
            .takeWhile { it != catchClause }
            .any { it.rethrowsCancellation() }
    }

    private fun KtCatchClause.rethrowsCancellation(): Boolean {
        val name = catchParameter?.name
        val type = catchParameter?.typeReference?.text
        return listOf(
            name != null,
            type?.endsWith("CancellationException") == true,
            catchBody?.propagatesCancellation(name.orEmpty()) == true,
        ).all { it }
    }

    private fun isCoroutineContext(element: KtElement): Boolean {
        val function = element.getParentOfType<KtNamedFunction>(strict = true)
        if (function?.hasModifier(KtTokens.SUSPEND_KEYWORD) == true) return true
        return generateSequence(element.parent) { it.parent }
            .takeWhile { it !is KtNamedFunction }
            .filterIsInstance<KtCallExpression>()
            .any { it.calleeExpression?.text in coroutineBuilders }
    }

    private companion object {
        val genericExceptionTypes = setOf("Exception", "Throwable")
    }
}

internal class NoDetachedCoroutineLifecycle(config: Config = Config.empty) : MobileRule(
    config,
    "NoDetachedCoroutineLifecycle",
    "Coroutines must be launched and shared from an explicit lifecycle owner.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.containingKtFile.isTestSource()) return
        if (expression.containingKtFile.isGenerated()) return
        when (expression.calleeExpression?.text) {
            "CoroutineScope" -> reportAdHocScope(expression)
            "async" -> reportCompletionCallbackAsync(expression)
            "shareIn", "stateIn" -> reportEagerExternalSharing(expression)
        }
    }

    private fun reportAdHocScope(expression: KtCallExpression) {
        report(expression, "Inject or receive a lifecycle-owned CoroutineScope; do not create one ad hoc.")
    }

    private fun reportCompletionCallbackAsync(expression: KtCallExpression) {
        val enclosingCalls = expression.parentsUntilFunction().filterIsInstance<KtCallExpression>()
        if (enclosingCalls.none { it.calleeExpression?.text == "invokeOnCompletion" }) return
        report(expression, "Do not start async work from invokeOnCompletion; use structured concurrency.")
    }

    private fun reportEagerExternalSharing(expression: KtCallExpression) {
        val arguments = expression.valueArguments.mapNotNull { it.getArgumentExpression()?.text }
        val sharingScope = arguments.firstOrNull()
        val isViolation = listOf(
            arguments.any { "SharingStarted.Eagerly" in it },
            sharingScope?.let(::isExternalScope) == true,
        ).all { it }
        if (isViolation) {
            report(
                expression,
                "Do not eagerly share on an externally supplied scope; bind sharing to owned lifecycle policy.",
            )
        }
    }

    override fun visitKtFile(file: KtFile) {
        if (file.isTestSource()) return super.visitKtFile(file)
        if (file.isGenerated()) return super.visitKtFile(file)
        file.collectDescendantsOfType<KtNameReferenceExpression>()
            .filter { it.getReferencedName() == "GlobalScope" }
            .forEach { report(it, "GlobalScope detaches work from every lifecycle.") }
        super.visitKtFile(file)
    }

    private fun isExternalScope(text: String): Boolean =
        text.matches(Regex("(?:this\\.)?[A-Za-z_][A-Za-z0-9_]*"))

    private fun KtElement.parentsUntilFunction(): Sequence<KtElement> =
        generateSequence(parent as? KtElement) { current ->
            current.parent as? KtElement
        }.takeWhile { it !is KtNamedFunction }
}

internal class CoroutineTestGuardrails(config: Config = Config.empty) : MobileRule(
    config,
    "CoroutineTestGuardrails",
    "Coroutine tests need observable assertions and performance checks need meaningful bounds.",
) {
    private val coroutineCalls = setOf("runTest", "runBlocking", "advanceUntilIdle", "runCurrent")

    override fun visitNamedFunction(function: KtNamedFunction) {
        super.visitNamedFunction(function)
        if (!function.isTest()) return
        val calls = function.collectDescendantsOfType<KtCallExpression>()
        reportMissingAssertion(function, calls)
        calls.forEach(::reportTautologicalTimingAssertion)
    }

    private fun KtNamedFunction.isTest(): Boolean =
        annotationEntries.any { it.shortName?.asString() == "Test" }

    private fun reportMissingAssertion(function: KtNamedFunction, calls: List<KtCallExpression>) {
        if (!function.isCoroutineTest(calls)) return
        if (calls.any { it.isAssertion() }) return
        report(function, "Coroutine test '${function.name}' has no observable assertion or verification.")
    }

    private fun KtNamedFunction.isCoroutineTest(calls: List<KtCallExpression>): Boolean {
        if (hasModifier(KtTokens.SUSPEND_KEYWORD)) return true
        return calls.any { it.calleeExpression?.text in coroutineCalls }
    }

    private fun KtCallExpression.isAssertion(): Boolean {
        val name = calleeExpression?.text.orEmpty()
        return listOf(
            name.startsWith("assert"),
            name.startsWith("verify"),
            name.startsWith("expect"),
            name in assertionNames,
        ).any { it }
    }

    private fun reportTautologicalTimingAssertion(call: KtCallExpression) {
        val binary = call.valueArguments.firstOrNull()?.getArgumentExpression() as? KtBinaryExpression
        val isViolation = listOf(
            call.calleeExpression?.text in timingAssertionCalls,
            binary?.operationReference?.text in nonNegativeOperators,
            binary?.right.isZeroLiteral(),
            binary?.left.isTimingValue(),
        ).all { it }
        if (isViolation) {
            report(
                requireNotNull(binary),
                "A non-negative timing assertion is tautological; assert a meaningful upper bound or behavior.",
            )
        }
    }

    private fun org.jetbrains.kotlin.psi.KtExpression?.isZeroLiteral(): Boolean =
        this?.text?.matches(Regex("0[LlFfDd]?")) == true

    private fun org.jetbrains.kotlin.psi.KtExpression?.isTimingValue(): Boolean {
        val normalized = this?.text?.lowercase().orEmpty()
        return timingTerms.any(normalized::contains)
    }

    private companion object {
        val assertionNames = setOf("fail", "shouldBe", "shouldEqual")
        val timingAssertionCalls = setOf("assertTrue", "assertThat")
        val nonNegativeOperators = setOf(">=", ">")
        val timingTerms = listOf("time", "duration", "elapsed", "latency", "millis", "nanos")
    }
}

class MobileGuardrailProvider : RuleSetProvider {
    override val ruleSetId: String = "letta-mobile-guardrails"

    override fun instance(config: Config): RuleSet = RuleSet(
        ruleSetId,
        listOf(
            NoAnyType(config),
            NoSelectStarInRoomDao(config),
            NoProcessGlobalMutableState(config),
            CancellationMustPropagate(config),
            NoDetachedCoroutineLifecycle(config),
            CoroutineTestGuardrails(config),
        ),
    )
}
