package com.letta.mobile.data.transport.appserver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppServerStopReasonTest {
    @Test
    fun requiresApprovalPausesTheRunAndNeverEndsTheTurn() {
        assertEquals(AppServerTurnBoundary.AwaitingApproval, AppServerStopReason.boundaryOf("requires_approval"))
        assertFalse(AppServerStopReason.isTerminal("requires_approval"))
    }

    @Test
    fun everyOfficialStopReasonOtherThanApprovalIsTerminal() {
        // @letta-ai/letta-client 0.32.10 resources/runs/runs.d.ts StopReasonType.
        val official = listOf(
            "end_turn", "error", "llm_api_error", "invalid_llm_response", "invalid_tool_call", "max_steps",
            "max_tokens_exceeded", "no_tool_call", "tool_rule", "cancelled", "insufficient_credits",
            "requires_approval", "context_window_overflow_in_system_prompt",
        )
        official.filterNot { it == "requires_approval" }.forEach { reason ->
            assertTrue(AppServerStopReason.isTerminal(reason), "$reason must end the turn")
        }
    }

    @Test
    fun failuresCancellationAndCompletionAreDistinguished() {
        listOf("error", "llm_api_error", "invalid_llm_response", "invalid_tool_call", "insufficient_credits", "context_window_overflow_in_system_prompt")
            .forEach { assertEquals(AppServerTurnBoundary.Failed, AppServerStopReason.boundaryOf(it), it) }
        assertEquals(AppServerTurnBoundary.Cancelled, AppServerStopReason.boundaryOf("cancelled"))
        listOf("end_turn", "max_steps", "max_tokens_exceeded", "no_tool_call", "tool_rule", "stop_sequence", "max_tokens", "length")
            .forEach { assertEquals(AppServerTurnBoundary.Completed, AppServerStopReason.boundaryOf(it), it) }
    }

    @Test
    fun aMissingReasonCompletesButAnUnrecognisedOneKeepsTheTurnOpen() {
        assertEquals(AppServerTurnBoundary.Completed, AppServerStopReason.boundaryOf(null))
        assertEquals(AppServerTurnBoundary.Continuing, AppServerStopReason.boundaryOf("tool_use"))
        assertFalse(AppServerStopReason.isTerminal("some_future_reason"))
    }
}
