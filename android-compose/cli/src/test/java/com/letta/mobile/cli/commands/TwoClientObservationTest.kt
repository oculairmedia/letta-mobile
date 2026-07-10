package com.letta.mobile.cli.commands

import com.letta.mobile.data.transport.ServerFrame
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * letta-mobile-r3i1z (B): unit guard for the two-client observer classifier —
 * the pure contract behind each live-sync round. Keeps the harness's
 * pass/fail logic honest in the gradle suite without needing a live server.
 */
class TwoClientObservationTest {
    private fun user(content: String, otid: String = "o") = ServerFrame.UserMessage(
        id = "cm-user-$otid", content = content, otid = otid, conversationId = "c",
    )

    private fun assistant(content: String, id: String = "cm-stream-x") = ServerFrame.AssistantMessage(
        id = id, content = content, conversationId = "c",
    )

    private fun terminal(status: String = "completed") = ServerFrame.TurnDone(
        id = "td", ts = "", turnId = "t", runId = "r", status = status,
    )

    @Test
    fun `healthy live turn passes all checks`() {
        val frames = listOf(
            user("round1 payload here"),
            assistant("Hi"),
            assistant("Hi there"),
            terminal(),
        )
        val result = TwoClientObservation.classify(frames, sentText = "round1 payload", gotTerminal = true)
        assertTrue(result.ok, "expected clean pass, got ${result.violations}")
    }

    @Test
    fun `missing terminal is a violation`() {
        val frames = listOf(user("round1 payload"), assistant("Hi there"))
        val result = TwoClientObservation.classify(frames, "round1 payload", gotTerminal = false)
        assertFalse(result.ok)
        assertTrue(result.violations.contains("observer_never_received_terminal"))
        assertTrue(result.violations.any { it.startsWith("terminal_count_") })
    }

    @Test
    fun `missing user echo is a violation (observer never saw the sender prompt)`() {
        val frames = listOf(assistant("Hi there"), terminal())
        val result = TwoClientObservation.classify(frames, "round1 payload", gotTerminal = true)
        assertFalse(result.ok)
        assertTrue(result.violations.contains("observer_missing_user_echo"))
    }

    @Test
    fun `missing assistant stream is a violation`() {
        val frames = listOf(user("round1 payload"), terminal())
        val result = TwoClientObservation.classify(frames, "round1 payload", gotTerminal = true)
        assertFalse(result.ok)
        assertTrue(result.violations.contains("observer_missing_assistant_stream"))
    }

    @Test
    fun `two terminals is a violation`() {
        val frames = listOf(user("round1 payload"), assistant("done"), terminal(), terminal())
        val result = TwoClientObservation.classify(frames, "round1 payload", gotTerminal = true)
        assertFalse(result.ok)
        assertEquals(true, result.violations.any { it == "terminal_count_2" })
    }

    @Test
    fun `out of order assistant before user is a violation`() {
        val frames = listOf(assistant("Hi there"), user("round1 payload"), terminal())
        val result = TwoClientObservation.classify(frames, "round1 payload", gotTerminal = true)
        assertFalse(result.ok)
        assertTrue(result.violations.any { it.startsWith("out_of_order") })
    }

    @Test
    fun `failed terminal status is a violation`() {
        val frames = listOf(user("round1 payload"), assistant("Hi there"), terminal(status = "failed"))
        val result = TwoClientObservation.classify(frames, "round1 payload", gotTerminal = true)
        assertFalse(result.ok)
        assertTrue(result.violations.contains("terminal_status_failed"))
    }
}
