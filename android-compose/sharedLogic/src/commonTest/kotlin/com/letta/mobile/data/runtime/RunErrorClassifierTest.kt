package com.letta.mobile.data.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * letta-mobile-bzvro.9 (F09): recorded-shape run errors classify into the kind the person can act
 * on; anything unrecognised stays the generic notice.
 */
class RunErrorClassifierTest {
    @Test
    fun recordedErrorPayloadsClassify() {
        val table = listOf(
            // Anthropic overloaded / OpenAI rate limit, as loop_error messages carry them.
            Case("Rate limit exceeded: 429 Too Many Requests", kind = RunErrorKind.RateLimited),
            Case("Error code: 529 - {'type': 'error', 'error': {'type': 'overloaded_error'}}", kind = RunErrorKind.RateLimited),
            Case("LLM call failed", apiError = "RATE_LIMIT_EXCEEDED", kind = RunErrorKind.RateLimited),
            // Letta Cloud credit exhaustion and OpenAI quota.
            Case("Request failed", apiError = "CREDIT_LIMIT_EXCEEDED", kind = RunErrorKind.CreditLimit),
            Case("You exceeded your current quota (insufficient_quota)", kind = RunErrorKind.CreditLimit),
            Case("Error code: 429 - insufficient_quota", kind = RunErrorKind.CreditLimit),
            Case("Model gpt-9 is not supported for this agent", apiError = "MODEL_NOT_SUPPORTED", kind = RunErrorKind.ModelNotSupported),
            Case("The model `claude-x` does not exist (model_not_found)", kind = RunErrorKind.ModelNotSupported),
            Case("prompt is too long: 210000 tokens > 200000 maximum", kind = RunErrorKind.ContextWindowExceeded),
            Case("This model's maximum context length is 128000 tokens", kind = RunErrorKind.ContextWindowExceeded),
            Case("run stopped", stopReason = "context_window_overflow_in_system_prompt", kind = RunErrorKind.ContextWindowExceeded),
            Case("fetch failed: ECONNRESET", kind = RunErrorKind.Network),
            Case("Request timed out", kind = RunErrorKind.Timeout),
            Case("Model provider error: Provider finish_reason: content_filter", kind = RunErrorKind.ContentFilter),
            Case("Model provider error: upstream 500", kind = RunErrorKind.ProviderError),
            Case("Something unexpected happened", kind = RunErrorKind.Other),
        )
        table.forEach { case ->
            assertEquals(case.kind, RunErrorClassifier.classify(case.message, case.stopReason, case.apiError), case.message)
        }
    }

    @Test
    fun eachKindOffersItsAction() {
        assertEquals(RunErrorAction.Retry, RunErrorKind.RateLimited.action)
        assertEquals(RunErrorAction.Retry, RunErrorKind.Network.action)
        assertEquals(RunErrorAction.SwitchModel, RunErrorKind.CreditLimit.action)
        assertEquals(RunErrorAction.SwitchModel, RunErrorKind.ModelNotSupported.action)
        assertEquals(RunErrorAction.Compact, RunErrorKind.ContextWindowExceeded.action)
        assertEquals(RunErrorAction.None, RunErrorKind.Other.action)
    }

    @Test
    fun aFailureRowsFixedCopyMapsBackToItsKind() {
        listOf(RunErrorKind.RateLimited, RunErrorKind.CreditLimit, RunErrorKind.ModelNotSupported, RunErrorKind.ContextWindowExceeded, RunErrorKind.Network)
            .forEach { kind ->
                val copy = TurnFailureNotices.messageFor(kind.family)
                assertEquals(kind, RunErrorClassifier.classifyDisplayed(copy), kind.name)
            }
    }

    @Test
    fun theGenericNoticeHasNoAction() {
        assertEquals(RunErrorKind.Other, RunErrorClassifier.classifyDisplayed(TurnFailureNotices.GENERIC_MESSAGE))
        assertNull(RunErrorClassifier.classifyDisplayed("  "))
    }

    @Test
    fun aClassifiedFailureKeepsItsFamilyThroughTheNotice() {
        val notice = TurnFailureNotices.forFailedTerminal(
            reason = "Error code: 402 - payment required: out of credits",
            deliveredAssistantContent = false,
        )!!
        assertEquals("credit_limit", notice.kind)
        assertEquals(RunErrorKind.CreditLimit, RunErrorClassifier.classifyDisplayed(notice.message))
        // A sanitized family on the wire is trusted as-is.
        val hinted = TurnFailureNotices.forFailedTerminal(reason = "copy", deliveredAssistantContent = false, kindHint = "context_window_exceeded")!!
        assertEquals("context_window_exceeded", hinted.kind)
    }

    private data class Case(
        val message: String,
        val stopReason: String? = null,
        val apiError: String? = null,
        val kind: RunErrorKind,
    )
}
