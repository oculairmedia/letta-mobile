package com.letta.mobile.data.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-br5g0: the dead-turn vs delivered-then-failed split and the
 * per-family copy that replaces the silent dead turn.
 */
class TurnFailureNoticesTest {
    @Test
    fun providerRefusalWithoutDeliveredContentGetsRefusalCopy() {
        val notice = TurnFailureNotices.forFailedTerminal(
            reason = "Model provider error: Provider finish_reason: content_filter",
            deliveredAssistantContent = false,
        )
        assertNotNull(notice)
        assertEquals("content_filter", notice.kind)
        assertTrue(notice.message.contains("refused"))
    }

    @Test
    fun completedReplySuppressesTheNoticeEntirely() {
        assertNull(
            TurnFailureNotices.forFailedTerminal(
                reason = "Model provider error: Provider finish_reason: content_filter",
                deliveredAssistantContent = true,
                mainReplyCompleted = true,
            ),
        )
    }

    @Test
    fun partialDeliveredContentStillSurfacesANotice() {
        val notice = TurnFailureNotices.forFailedTerminal(
            reason = "Model provider error: Provider finish_reason: content_filter",
            deliveredAssistantContent = true,
            mainReplyCompleted = false,
        )
        assertNotNull(notice)
        assertEquals("content_filter", notice.kind)
    }

    @Test
    fun missingReasonFallsBackToGenericCopy() {
        val notice = TurnFailureNotices.forFailedTerminal(reason = null, deliveredAssistantContent = false)
        assertNotNull(notice)
        assertEquals("other", notice.kind)
        assertEquals(TurnFailureNotices.GENERIC_MESSAGE, notice.message)
    }

    @Test
    fun eachFamilyGetsItsOwnCopy() {
        val reasons = listOf(
            "Model provider error: Provider finish_reason: content_filter",
            "HTTP 429 rate limit exceeded",
            "request timed out after 60000ms",
            "Model provider error: upstream returned 500",
            "empty response from model",
            "Conversation conv-1 is busy with run local-run-7",
            "Run ended while waiting for approval of 1 tool call",
            "invalid tool call ids: toolu-abc",
            "turn aborted by client cancel",
        )
        val notices = reasons.map {
            TurnFailureNotices.forFailedTerminal(it, deliveredAssistantContent = false)!!
        }
        assertEquals(reasons.size, notices.map { it.kind }.toSet().size)
        assertEquals(reasons.size, notices.map { it.message }.toSet().size)
    }

    @Test
    fun copyNeverEchoesTheRawReason() {
        val secret = "Model provider error: token sk-live-abcdef leaked in prompt"
        val notice = TurnFailureNotices.forFailedTerminal(secret, deliveredAssistantContent = false)
        assertNotNull(notice)
        assertFalse(notice.message.contains("sk-live-abcdef"))
        assertFalse(notice.kind.contains("sk-live-abcdef"))
    }

    @Test
    fun onlyTerminalSuccessStopsCountAsMainReplyComplete() {
        assertTrue(TurnFailureNotices.isCompletedMainReplyStopReason("end_turn"))
        assertTrue(TurnFailureNotices.isCompletedMainReplyStopReason("stop_sequence"))
        assertTrue(TurnFailureNotices.isCompletedMainReplyStopReason("max_tokens"))
        assertFalse(TurnFailureNotices.isCompletedMainReplyStopReason("requires_approval"))
        assertFalse(TurnFailureNotices.isCompletedMainReplyStopReason("error"))
        assertFalse(TurnFailureNotices.isCompletedMainReplyStopReason("cancelled"))
        assertFalse(TurnFailureNotices.isCompletedMainReplyStopReason(null))
        assertFalse(TurnFailureNotices.isCompletedMainReplyStopReason(""))
    }

    @Test
    fun stopReasonFromStreamDeltaBodyReadsNestedAndBareForms() {
        assertEquals(
            "end_turn",
            TurnFailureNotices.stopReasonFromStreamDeltaBody(
                """{"type":"stream_delta","delta":{"message_type":"stop_reason","stop_reason":"end_turn"}}""",
            ),
        )
        assertEquals(
            "error",
            TurnFailureNotices.stopReasonFromStreamDeltaBody("""{"stop_reason":"error"}"""),
        )
        assertNull(TurnFailureNotices.stopReasonFromStreamDeltaBody("{not-json"))
        assertNull(TurnFailureNotices.stopReasonFromStreamDeltaBody("""{"delta":{}}"""))
    }
}
