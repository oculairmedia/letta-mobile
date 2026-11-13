package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.api.Config
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Flags raw Throwable text (`.message`, `.localizedMessage`, `.toString()`, `stackTraceToString()`)
 * passed directly into a client-visible error or a telemetry value: `adminError(...)`,
 * `AdminRpcClientError(...)`, a response/refusal builder or a hand-built JSON error frame in
 * `controller/node/iroh/`, `Telemetry.event(...)`, or an `attr` value. Exception text can carry
 * paths, upstream bodies, tokens and other user data, and it is not a stable contract.
 *
 * Purely syntactic, no data flow: the Throwable text must sit in the argument itself, possibly behind
 * parentheses, an elvis, string concatenation, a string template or a receiver chain such as
 * `e.message?.take(200)`. Passing it through any other call first (a redacting formatter, for
 * example) is not reported. A receiver counts as a Throwable when it is an enclosing catch
 * parameter or is named like one (`e`, `t`, `ex`, `err`, `error`, `cause`, `throwable`, `exception`,
 * `failure`, or ending in `Error`, `Exception`, `Throwable` or `Cause`).
 */
internal class ExceptionMessageToClientOrTelemetry(config: Config = Config.empty) : MobileRule(
    config,
    "ExceptionMessageToClientOrTelemetry",
    "Raw exception text must not flow into client errors, response frames or telemetry.",
) {
    private var inIrohController = false
    private val reported = mutableListOf<TextRange>()

    override fun visitKtFile(file: KtFile) {
        if (file.isTestSource() || file.isGenerated()) return
        inIrohController = IROH_CONTROLLER in file.normalizedPath()
        reported.clear()
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        if (!isSink(expression)) return
        expression.valueArguments.mapNotNull { it.getArgumentExpression() }
            .firstOrNull(::carriesThrowableText)
            ?.let(::reportLeak)
    }

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        if (expression.operationReference.getReferencedName() != "attr") return
        expression.right?.takeIf(::carriesThrowableText)?.let(::reportLeak)
    }

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)
        if (!inIrohController || JSON_ERROR_KEYS.none { it in expression.text }) return
        if (carriesThrowableText(expression)) reportLeak(expression)
    }

    private fun isSink(call: KtCallExpression): Boolean {
        val name = call.calleeExpression?.text ?: return false
        return when {
            name in GLOBAL_SINKS -> true
            name == "event" -> call.receiverText()?.endsWith("elemetry") == true
            else -> inIrohController && IROH_BUILDER.matches(name)
        }
    }

    private fun KtCallExpression.receiverText(): String? =
        (parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == this }?.receiverExpression?.text

    private fun carriesThrowableText(expression: KtExpression): Boolean = when (expression) {
        is KtParenthesizedExpression -> expression.expression?.let(::carriesThrowableText) == true
        is KtBinaryExpression -> listOfNotNull(expression.left, expression.right).any(::carriesThrowableText)
        is KtStringTemplateExpression -> expression.entries.any { entry ->
            entry.expression?.let(::carriesThrowableText) == true
        }
        is KtQualifiedExpression -> isThrowableText(expression) ||
            carriesThrowableText(expression.receiverExpression)
        else -> false
    }

    private fun isThrowableText(expression: KtQualifiedExpression): Boolean {
        val selector = expression.selectorExpression ?: return false
        val member = when (selector) {
            is KtCallExpression -> selector.calleeExpression?.text?.takeIf { selector.valueArguments.isEmpty() }
            is KtNameReferenceExpression -> selector.getReferencedName()
            else -> null
        }
        val receiver = expression.receiverExpression as? KtNameReferenceExpression
        return member in THROWABLE_TEXT && receiver != null && isThrowable(receiver)
    }

    private fun isThrowable(reference: KtNameReferenceExpression): Boolean {
        val name = reference.getReferencedName()
        if (THROWABLE_NAME.matches(name)) return true
        return generateSequence(reference.parent) { it.parent }
            .filterIsInstance<KtCatchClause>()
            .any { it.catchParameter?.name == name }
    }

    private fun reportLeak(expression: KtExpression) {
        val range = expression.textRange
        if (reported.any { it.intersects(range) }) return
        reported += range
        report(
            expression,
            "Do not forward raw exception text to a client error, response frame or telemetry value. " +
                "Send a stable error code or reason instead, and log the Throwable itself (Telemetry.error) " +
                "or pass the text through an explicit redacting formatter.",
        )
    }

    private companion object {
        const val IROH_CONTROLLER = "/controller/node/iroh/"
        const val STACK_TRACE = "stackTraceToString"
        val GLOBAL_SINKS = setOf("adminError", "AdminRpcClientError", "attr")
        val IROH_BUILDER = Regex(
            "[A-Za-z]*(Failure|Refusal|Rejection|Denied|ErrorEnvelope|ErrorFrame|ErrorResponse)" +
                "|errorEnvelope|errorFrame",
        )
        val JSON_ERROR_KEYS = listOf("\"error\"", "\"message\"")
        val THROWABLE_TEXT = setOf("message", "localizedMessage", "toString", STACK_TRACE)
        val THROWABLE_NAME = Regex(
            "e|t|ex|err|error|cause|throwable|exception|failure|[a-z][A-Za-z0-9]*(Error|Exception|Throwable|Cause)",
        )
    }
}
