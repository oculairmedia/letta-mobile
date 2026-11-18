package com.letta.mobile.quality.guardrails

import com.letta.mobile.quality.MobileRule
import com.letta.mobile.quality.isGenerated
import com.letta.mobile.quality.isTestSource
import io.gitlab.arturbosch.detekt.api.Config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.psiUtil.getParentOfType

/** A [MobileRule] that only inspects production sources: test and generated files are skipped. */
internal abstract class ProductionSourceRule(config: Config, name: String, description: String) :
    MobileRule(config, name, description) {
    override fun visitKtFile(file: KtFile) {
        if (file.isTestSource() || file.isGenerated()) return
        super.visitKtFile(file)
    }
}

/**
 * Flags `calculateLeftPadding(...)` and `calculateRightPadding(...)` in production code. Hardcoding
 * left/right padding ignores RTL layout direction; callers must use
 * `calculateStartPadding(LocalLayoutDirection.current)` or `calculateEndPadding(...)` instead.
 */
internal class NoHardcodedPaddingDirection(config: Config = Config.empty) : ProductionSourceRule(
    config,
    "NoHardcodedPaddingDirection",
    "PaddingValues must be resolved with calculateStartPadding/calculateEndPadding rather than left/right.",
) {
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
internal class NoDeprecatedBrowserUnloadEvent(config: Config = Config.empty) : ProductionSourceRule(
    config,
    "NoDeprecatedBrowserUnloadEvent",
    "Browser teardown listeners must use pagehide or visibilitychange instead of unload.",
) {
    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val event = expression.deprecatedUnloadEvent() ?: return
        report(
            expression,
            "Do not listen for $event; use \"pagehide\" or \"visibilitychange\" so bfcache and " +
                "modern Permissions-Policy remain intact.",
        )
    }

    private fun KtCallExpression.deprecatedUnloadEvent(): String? = valueArguments
        .takeIf { calleeExpression?.text == "addEventListener" }
        ?.firstOrNull()
        ?.getArgumentExpression()
        ?.text
        ?.takeIf { it in DEPRECATED_UNLOAD_EVENTS }

    private companion object {
        val DEPRECATED_UNLOAD_EVENTS = setOf("\"unload\"", "\"beforeunload\"")
    }
}

/**
 * Flags `<flow>.value = <flow>.value.copy(...)` read-modify-write assignments in production code.
 * Reading `.value` and assigning `.value = ...` is non-atomic and clobbers concurrent updates; use
 * `<flow>.update { it.copy(...) }` instead.
 */
internal class NoNonAtomicStateFlowSelfCopy(config: Config = Config.empty) : ProductionSourceRule(
    config,
    "NoNonAtomicStateFlowSelfCopy",
    "MutableStateFlow read-modify-write updates must use .update { it.copy(...) } " +
        "instead of .value = .value.copy(...).",
) {
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
internal class NoUnrememberedToolFactsInComposable(config: Config = Config.empty) : ProductionSourceRule(
    config,
    "NoUnrememberedToolFactsInComposable",
    "Tool call .facts() JSON parsing inside @Composable functions must be wrapped in remember.",
) {
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
