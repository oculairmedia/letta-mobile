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
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
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
        val file = property.containingKtFile
        if (file.isTestSource() || file.isGenerated()) return
        val owner = property.parent
        val isGlobal = owner is KtFile ||
            (owner is org.jetbrains.kotlin.psi.KtClassBody && owner.parent is KtObjectDeclaration)
        if (!isGlobal || property.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.CONST_KEYWORD)) return
        val type = property.typeReference?.text.orEmpty()
        val factory = (property.initializer as? KtCallExpression)?.calleeExpression?.text
        val mutableType = listOf(
            "MutableMap", "MutableList", "MutableSet", "MutableCollection", "MutableState", "MutableStateFlow",
        ).any(type::contains)
        val mutableVarCollection = property.isVar && listOf("Map", "List", "Set", "Collection", "StateFlow", "State")
            .any(type::contains)
        if (factory in mutableFactories || mutableType || mutableVarCollection) {
            report(property, "Move mutable state into an injected, lifecycle-owned instance.")
        }
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
        val caught = catchClause.catchParameter?.typeReference?.text?.removePrefix("kotlin.")
        val parameter = catchClause.catchParameter?.name
        val body = catchClause.catchBody
        if (caught in setOf("Exception", "Throwable")) {
            if (parameter != null && body != null && isCoroutineContext(catchClause)) {
                val propagates = body.collectDescendantsOfType<KtThrowExpression>().any { thrown ->
                    val expression = thrown.thrownExpression?.text.orEmpty()
                    expression == parameter || "CancellationException" in expression
                } || cancellationHandledByEarlierCatch(catchClause)
                if (!propagates) report(catchClause, "Rethrow CancellationException before handling $caught.")
            }
        }
    }

    private fun cancellationHandledByEarlierCatch(catchClause: KtCatchClause): Boolean {
        val tryExpression = catchClause.parent as? KtTryExpression ?: return false
        return tryExpression.catchClauses.takeWhile { it != catchClause }.any { earlier ->
            val name = earlier.catchParameter?.name ?: return@any false
            earlier.catchParameter?.typeReference?.text?.endsWith("CancellationException") == true &&
                earlier.catchBody?.collectDescendantsOfType<KtThrowExpression>()
                    ?.any { it.thrownExpression?.text == name } == true
        }
    }

    private fun isCoroutineContext(element: KtElement): Boolean {
        val function = element.getParentOfType<KtNamedFunction>(strict = true)
        val insideSuspend = function?.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SUSPEND_KEYWORD) == true
        val insideBuilder = generateSequence(element.parent) { it.parent }
            .takeWhile { it !is KtNamedFunction }
            .filterIsInstance<KtCallExpression>()
            .any { it.calleeExpression?.text in coroutineBuilders }
        return insideSuspend || insideBuilder
    }
}

internal class NoDetachedCoroutineLifecycle(config: Config = Config.empty) : MobileRule(
    config,
    "NoDetachedCoroutineLifecycle",
    "Coroutines must be launched and shared from an explicit lifecycle owner.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.containingKtFile.isTestSource() ||
            expression.containingKtFile.isGenerated()
        ) return
        val callee = expression.calleeExpression?.text ?: return
        if (callee == "CoroutineScope") {
            report(expression, "Inject or receive a lifecycle-owned CoroutineScope; do not create one ad hoc.")
        }
        if (callee == "async" && expression.parentsUntilFunction().filterIsInstance<KtCallExpression>()
                .any { it.calleeExpression?.text == "invokeOnCompletion" }
        ) {
            report(expression, "Do not start async work from invokeOnCompletion; use structured concurrency.")
        }
        if (callee in setOf("shareIn", "stateIn")) {
            val args = expression.valueArguments.mapNotNull { it.getArgumentExpression()?.text }
            if (args.any { "SharingStarted.Eagerly" in it } && args.firstOrNull()?.let(::isExternalScope) == true) {
                report(
                    expression,
                    "Do not eagerly share on an externally supplied scope; bind sharing to owned lifecycle policy.",
                )
            }
        }
    }

    override fun visitKtFile(file: KtFile) {
        if (!file.isTestSource() && !file.isGenerated()) {
            file.collectDescendantsOfType<org.jetbrains.kotlin.psi.KtNameReferenceExpression>()
                .filter { it.getReferencedName() == "GlobalScope" }
                .forEach { report(it, "GlobalScope detaches work from every lifecycle.") }
        }
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
        val isTest = function.annotationEntries.any { it.shortName?.asString() == "Test" }
        if (!isTest) return
        val calls = function.collectDescendantsOfType<KtCallExpression>()
        if ((function.hasModifier(org.jetbrains.kotlin.lexer.KtTokens.SUSPEND_KEYWORD) ||
                calls.any { it.calleeExpression?.text in coroutineCalls }) &&
            calls.none { it.calleeExpression?.text.orEmpty().let(::isAssertion) }
        ) {
            report(function, "Coroutine test '${function.name}' has no observable assertion or verification.")
        }
        calls.filter { it.calleeExpression?.text in setOf("assertTrue", "assertThat") }.forEach { call ->
            val binary = call.valueArguments.firstOrNull()?.getArgumentExpression() as? KtBinaryExpression
                ?: return@forEach
            val left = binary.left?.text.orEmpty()
            val right = binary.right?.text.orEmpty()
            if (binary.operationReference.text in setOf(">=", ">") && right.matches(Regex("0(?:[LlFfDd])?")) &&
                listOf("time", "duration", "elapsed", "latency", "millis", "nanos").any(left.lowercase()::contains)
            ) {
                report(
                    binary,
                    "A non-negative timing assertion is tautological; assert a meaningful upper bound or behavior.",
                )
            }
        }
    }

    private fun isAssertion(name: String): Boolean = name.startsWith("assert") || name.startsWith("verify") ||
        name.startsWith("expect") || name == "fail" || name == "shouldBe" || name == "shouldEqual"
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
