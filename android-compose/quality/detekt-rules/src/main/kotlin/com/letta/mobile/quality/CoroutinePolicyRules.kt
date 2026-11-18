package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.api.Config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/**
 * Flags `runBlocking` in production code. It parks the calling thread until the coroutine
 * finishes, which freezes a UI thread and can deadlock a dispatcher that the blocked work needs.
 * Tests and `fun main` entry points (top level or `@JvmStatic`) are exempt.
 */
internal class NoRunBlockingInProduction(config: Config = Config.empty) : MobileRule(
    config,
    "NoRunBlockingInProduction",
    "Production code must not block a thread on a coroutine with runBlocking.",
) {
    override fun visitKtFile(file: KtFile) {
        if (file.isTestSource() || file.isGenerated()) return
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != "runBlocking") return
        if (expression.getParentOfType<KtNamedFunction>(strict = true)?.name == "main") return
        report(
            expression,
            "Do not call runBlocking in production code: make the caller suspend, or launch on an " +
                "injected lifecycle-owned CoroutineScope and expose the result as a Flow or callback.",
        )
    }
}

/**
 * Flags coroutine buffers with no capacity bound in production code: `Channel(UNLIMITED)`,
 * `produce(capacity = UNLIMITED)`, `.buffer(UNLIMITED)` and a `MutableSharedFlow` whose `replay` or
 * `extraBufferCapacity` is `Int.MAX_VALUE` / `UNLIMITED`. A slow or stalled consumer then grows memory
 * without limit instead of applying back-pressure.
 *
 * Opt-out: a `// overflow: <why unbounded is safe here>` comment on the same line as the call or the
 * line before it, so the decision is written next to the code.
 */
internal class UnboundedBufferNeedsPolicy(config: Config = Config.empty) : MobileRule(
    config,
    "UnboundedBufferNeedsPolicy",
    "Coroutine channels and shared flows need a bounded capacity and an overflow policy.",
) {
    override fun visitKtFile(file: KtFile) {
        if (file.isTestSource() || file.isGenerated()) return
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val unbounded = when (expression.calleeExpression?.text) {
            "Channel", "produce", "buffer" -> expression.valueArguments.firstOrNull(::isCapacityArgument)
            "MutableSharedFlow" -> expression.valueArguments.firstOrNull(::isSharedFlowBuffer)
            else -> null
        }
        if (unbounded?.getArgumentExpression()?.text?.let(::isUnbounded) != true) return
        if (hasOverflowComment(expression)) return
        report(
            expression,
            "Unbounded coroutine buffer: give it a fixed capacity and an onBufferOverflow policy " +
                "(DROP_OLDEST, DROP_LATEST or SUSPEND), or justify it with an '// overflow: <reason>' " +
                "comment on this or the previous line.",
        )
    }

    private fun isCapacityArgument(argument: KtValueArgument): Boolean {
        val name = argument.getArgumentName()?.asName?.asString()
        return name == null || name == "capacity"
    }

    private fun isSharedFlowBuffer(argument: KtValueArgument): Boolean {
        val name = argument.getArgumentName()?.asName?.asString()
        return name == null || name == "replay" || name == "extraBufferCapacity"
    }

    private fun isUnbounded(text: String): Boolean = text.substringAfterLast('.') == "UNLIMITED" ||
        text.replace(" ", "") in setOf("Int.MAX_VALUE", "kotlin.Int.MAX_VALUE")

    private fun hasOverflowComment(expression: KtCallExpression): Boolean {
        val lines = expression.containingKtFile.text.lines()
        val line = lineOf(expression)
        return listOf(line, line - 1)
            .mapNotNull { lines.getOrNull(it) }
            .any { OVERFLOW_COMMENT.containsMatchIn(it) }
    }

    private fun lineOf(expression: KtCallExpression): Int =
        expression.containingKtFile.text.substring(0, expression.textRange.startOffset).count { it == '\n' }

    private companion object {
        val OVERFLOW_COMMENT = Regex("//.*\\boverflow:")
    }
}

/**
 * Flags `calculateLeftPadding(...)` and `calculateRightPadding(...)` in production code. Hardcoding
 * left/right padding ignores RTL layout direction; callers must use
 * `calculateStartPadding(LocalLayoutDirection.current)` or `calculateEndPadding(...)` instead.
 */
internal class NoHardcodedPaddingDirection(config: Config = Config.empty) : MobileRule(
    config,
    "NoHardcodedPaddingDirection",
    "PaddingValues must be resolved with calculateStartPadding/calculateEndPadding rather than left/right.",
) {
    override fun visitKtFile(file: KtFile) {
        if (file.isTestSource() || file.isGenerated()) return
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val callee = expression.calleeExpression?.text ?: return
        if (callee !in DIRECTION_UNAWARE_CALLS) return
        report(
            expression,
            "Do not call $callee(...); use calculateStartPadding(LocalLayoutDirection.current) or " +
                "calculateEndPadding(LocalLayoutDirection.current) so RTL and LTR layouts both align.",
        )
    }

    private companion object {
        val DIRECTION_UNAWARE_CALLS = setOf("calculateLeftPadding", "calculateRightPadding")
    }
}

/**
 * Flags browser `addEventListener("unload", ...)` and `addEventListener("beforeunload", ...)` in
 * production code. The `unload` event disables bfcache, is blocked by modern Permissions-Policy
 * defaults, and does not fire reliably on mobile browsers; use `"pagehide"` or `"visibilitychange"`.
 */
internal class NoDeprecatedBrowserUnloadEvent(config: Config = Config.empty) : MobileRule(
    config,
    "NoDeprecatedBrowserUnloadEvent",
    "Browser teardown listeners must use pagehide or visibilitychange instead of unload.",
) {
    override fun visitKtFile(file: KtFile) {
        if (file.isTestSource() || file.isGenerated()) return
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (expression.calleeExpression?.text != "addEventListener") return
        val firstArg = expression.valueArguments.firstOrNull()?.getArgumentExpression()?.text ?: return
        if (firstArg !in DEPRECATED_UNLOAD_EVENTS) return
        report(
            expression,
            "Do not listen for $firstArg; use \"pagehide\" or \"visibilitychange\" so bfcache and " +
                "modern Permissions-Policy remain intact.",
        )
    }

    private companion object {
        val DEPRECATED_UNLOAD_EVENTS = setOf("\"unload\"", "\"beforeunload\"")
    }
}

/**
 * Flags `<flow>.value = <flow>.value.copy(...)` read-modify-write assignments in production code.
 * Reading `.value` and assigning `.value = ...` is non-atomic and clobbers concurrent updates; use
 * `<flow>.update { it.copy(...) }` instead.
 */
internal class NoNonAtomicStateFlowSelfCopy(config: Config = Config.empty) : MobileRule(
    config,
    "NoNonAtomicStateFlowSelfCopy",
    "MutableStateFlow read-modify-write updates must use .update { it.copy(...) } instead of .value = .value.copy(...).",
) {
    override fun visitKtFile(file: KtFile) {
        if (file.isTestSource() || file.isGenerated()) return
        super.visitKtFile(file)
    }

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        val lhsReceiver = expression.takeIf { it.operationToken == KtTokens.EQ }?.left?.valueReceiver() ?: return
        if (lhsReceiver != expression.right.copyValueReceiver()) return
        report(
            expression,
            "Non-atomic read-modify-write on '$lhsReceiver.value'; use '$lhsReceiver.update { it.copy(...) }' " +
                "so concurrent updates are not lost.",
        )
    }

    private fun KtElement?.valueReceiver(): String? {
        val dot = this as? KtDotQualifiedExpression
        return dot?.takeIf { it.selectorExpression?.text == "value" }?.receiverExpression?.text?.trim()
    }

    private fun KtElement?.copyValueReceiver(): String? {
        val outerDot = this as? KtDotQualifiedExpression
        val call = outerDot?.selectorExpression as? KtCallExpression
        return outerDot?.takeIf { call?.calleeExpression?.text == "copy" }?.receiverExpression?.valueReceiver()
    }
}

/**
 * Flags calling `.facts()` on a tool call directly inside a `@Composable` function outside a
 * `remember { ... }` block, which re-parses the tool call's JSON arguments on every recomposition.
 */
internal class NoUnrememberedToolFactsInComposable(config: Config = Config.empty) : MobileRule(
    config,
    "NoUnrememberedToolFactsInComposable",
    "Tool call .facts() JSON parsing inside @Composable functions must be wrapped in remember.",
) {
    override fun visitKtFile(file: KtFile) {
        if (file.isTestSource() || file.isGenerated()) return
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!expression.isZeroArgFactsReceiverCall()) return
        val enclosingFunction = expression.getParentOfType<KtNamedFunction>(strict = true) ?: return
        if (enclosingFunction.requiresRememberedFacts() && !expression.isInsideRememberCall(enclosingFunction)) {
            report(
                expression,
                "Wrap toolCall.facts() in remember(toolCall.arguments) { ... } so JSON arguments are not " +
                    "re-parsed on every recomposition.",
            )
        }
    }

    private fun KtCallExpression.isZeroArgFactsReceiverCall(): Boolean {
        if (calleeExpression?.text != "facts") return false
        return valueArguments.isEmpty() && parent is KtDotQualifiedExpression
    }

    private fun KtNamedFunction.requiresRememberedFacts(): Boolean =
        annotationEntries.any { it.shortName?.asString() == "Composable" } && name?.startsWith("remember") != true

    private fun KtElement.isInsideRememberCall(boundary: KtNamedFunction): Boolean =
        generateSequence(parent) { it.parent }
            .takeWhile { it != boundary }
            .filterIsInstance<KtCallExpression>()
            .any { it.calleeExpression?.text?.startsWith("remember") == true }
}
