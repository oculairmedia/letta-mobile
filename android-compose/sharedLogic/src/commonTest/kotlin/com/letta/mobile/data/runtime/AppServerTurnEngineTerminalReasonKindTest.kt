package com.letta.mobile.data.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * letta-mobile-aktss: terminal-failure reason classification. The raw reason
 * stays redacted in telemetry (o0atv), so the classifier must return only
 * fixed category tokens — these tests pin the family mapping for the failure
 * shapes letta-code actually produces.
 */
class AppServerTurnEngineTerminalReasonKindTest {
    @Test
    fun blankReasonYieldsNull() {
        assertNull(terminalReasonKind(null))
        assertNull(terminalReasonKind(""))
        assertNull(terminalReasonKind("   "))
    }

    @Test
    fun providerRefusalClassifiesAsContentFilter() {
        assertEquals(
            "content_filter",
            terminalReasonKind("Model provider error: Provider finish_reason: content_filter"),
        )
        assertEquals("content_filter", terminalReasonKind("stop_reason refusal from upstream"))
    }

    @Test
    fun approvalPendingClassified() {
        assertEquals(
            "approval_pending",
            terminalReasonKind("Run ended while waiting for approval of 1 tool call"),
        )
    }

    @Test
    fun conversationBusyClassified() {
        assertEquals(
            "conversation_busy",
            terminalReasonKind("Conversation conv-1 is busy with run local-run-7"),
        )
    }

    @Test
    fun rateLimitAndTimeoutClassified() {
        assertEquals("rate_limited", terminalReasonKind("HTTP 429 rate limit exceeded"))
        assertEquals("timeout", terminalReasonKind("request timed out after 60000ms"))
    }

    @Test
    fun genericProviderErrorClassified() {
        assertEquals(
            "provider_error",
            terminalReasonKind("Model provider error: upstream returned 500"),
        )
    }

    @Test
    fun abortClassified() {
        assertEquals("aborted", terminalReasonKind("Tool execution interrupted by turn termination"))
    }

    @Test
    fun unknownReasonFallsBackToOther() {
        assertEquals("other", terminalReasonKind("something entirely novel went wrong"))
    }

    @Test
    fun tokenIsNeverASubstringOfSecretBearingReason() {
        // The classifier must return a fixed token, not echo reason content.
        val secretReason = "provider rejected: api key sk-abc123 invalid"
        val kind = terminalReasonKind(secretReason)
        assertEquals("provider_error", kind)
    }
}
