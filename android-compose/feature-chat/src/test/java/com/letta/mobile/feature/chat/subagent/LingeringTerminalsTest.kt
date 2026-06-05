package com.letta.mobile.feature.chat.subagent

import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * letta-mobile-29h9u: unit coverage for the lingering-terminal visibility
 * rule — the pure [withLingeringTerminals] transform and the
 * [ActiveSubagent.isTerminal] / [ActiveSubagent.Status.isTerminal] helpers.
 *
 * Spec: a completed/failed chip must stay reviewable for [TERMINAL_LINGER_MS]
 * instead of vanishing instantly under the active-only rule, while still
 * dropping once its linger window expires. Running chips are unaffected.
 */
@Tag("unit")
class LingeringTerminalsTest {

    private fun running(id: String) = ActiveSubagent(
        id = id,
        description = "desc $id",
        subagentType = "general",
        status = ActiveSubagent.Status.RUNNING,
    )

    private fun terminal(
        id: String,
        status: ActiveSubagent.Status,
        terminalAt: Long?,
    ) = ActiveSubagent(
        id = id,
        description = "desc $id",
        subagentType = "general",
        status = status,
        terminalAt = terminalAt,
    )

    @Test
    fun `isTerminal helper is true only for completed and failed`() {
        assertFalse(ActiveSubagent.Status.RUNNING.isTerminal)
        assertTrue(ActiveSubagent.Status.COMPLETED.isTerminal)
        assertTrue(ActiveSubagent.Status.FAILED.isTerminal)

        assertFalse(running("a").isTerminal)
        assertTrue(running("a").copy(status = ActiveSubagent.Status.COMPLETED).isTerminal)
        assertTrue(running("a").copy(status = ActiveSubagent.Status.FAILED).isTerminal)
    }

    @Test
    fun `running chips always pass regardless of clock`() {
        val list = persistentListOf(running("a"), running("b"))
        assertEquals(listOf("a", "b"), list.withLingeringTerminals(now = 0L).map { it.id })
        assertEquals(listOf("a", "b"), list.withLingeringTerminals(now = Long.MAX_VALUE).map { it.id })
    }

    @Test
    fun `freshly terminal chip lingers within the window`() {
        val list = persistentListOf(
            running("run"),
            terminal("done", ActiveSubagent.Status.COMPLETED, terminalAt = 1_000L),
        )
        // 1s after it went terminal — still inside the 8s window.
        val visible = list.withLingeringTerminals(now = 1_000L + 1_000L)
        assertEquals(listOf("run", "done"), visible.map { it.id })
    }

    @Test
    fun `terminal chip drops once the linger window expires`() {
        val list = persistentListOf(
            running("run"),
            terminal("done", ActiveSubagent.Status.COMPLETED, terminalAt = 1_000L),
        )
        // Exactly at the boundary -> expired (>= window).
        val atBoundary = list.withLingeringTerminals(now = 1_000L + ActiveSubagent.TERMINAL_LINGER_MS)
        assertEquals(listOf("run"), atBoundary.map { it.id })

        // Well past the boundary -> still gone.
        val past = list.withLingeringTerminals(now = 1_000L + ActiveSubagent.TERMINAL_LINGER_MS + 5_000L)
        assertEquals(listOf("run"), past.map { it.id })
    }

    @Test
    fun `failed chips linger just like completed ones`() {
        val list = persistentListOf(
            terminal("boom", ActiveSubagent.Status.FAILED, terminalAt = 0L),
        )
        assertEquals(listOf("boom"), list.withLingeringTerminals(now = 2_000L).map { it.id })
        assertEquals(emptyList<String>(), list.withLingeringTerminals(now = ActiveSubagent.TERMINAL_LINGER_MS).map { it.id })
    }

    @Test
    fun `terminal chip without a stamp is dropped defensively`() {
        val list = persistentListOf(
            running("run"),
            terminal("orphan", ActiveSubagent.Status.COMPLETED, terminalAt = null),
        )
        // An unstamped terminal must never linger forever.
        assertEquals(listOf("run"), list.withLingeringTerminals(now = 0L).map { it.id })
    }

    @Test
    fun `order is preserved`() {
        val list = persistentListOf(
            running("a"),
            terminal("b", ActiveSubagent.Status.COMPLETED, terminalAt = 0L),
            running("c"),
        )
        assertEquals(listOf("a", "b", "c"), list.withLingeringTerminals(now = 1_000L).map { it.id })
    }
}
