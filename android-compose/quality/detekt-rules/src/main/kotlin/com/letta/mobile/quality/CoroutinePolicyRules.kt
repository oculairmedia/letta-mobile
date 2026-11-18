package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.api.Config
import org.jetbrains.kotlin.psi.KtCallExpression
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
