package com.letta.mobile.feature.chat.subagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * letta-mobile-dvobc / xrth2 / pvrrm: pure unit coverage for the chip-model
 * derivations that drive the active-bar render — the progress-ring FILL
 * (fraction), the ring STATE->color mapping (incl. the new "stuck" heuristic),
 * the unambiguous copy/state mapping, and the unified `kind` taxonomy. No
 * Android/Compose deps so these run fast on the JVM.
 */
@Tag("unit")
class ActiveSubagentRingTest {

    private fun running(
        progress: SubagentTodoProgress? = null,
        lastUpdateAt: Long? = null,
        kind: ActiveSubagent.Kind = ActiveSubagent.Kind.SUBAGENT,
    ) = ActiveSubagent(
        id = "id",
        description = "desc",
        subagentType = "general",
        status = ActiveSubagent.Status.RUNNING,
        kind = kind,
        progress = progress,
        lastUpdateAt = lastUpdateAt,
    )

    // ---- BEAD 1: fraction -> ring fill ------------------------------------

    @Test
    fun `ring fraction is the todo completion fraction while running`() {
        assertEquals(0.5f, running(SubagentTodoProgress(2, 4)).ringFraction)
        assertEquals(0.25f, running(SubagentTodoProgress(1, 4)).ringFraction)
    }

    @Test
    fun `ring fraction is zero when no todos yet`() {
        assertEquals(0f, running(progress = null).ringFraction)
        // total 0 must not produce NaN.
        assertEquals(0f, running(SubagentTodoProgress(0, 0)).ringFraction)
    }

    @Test
    fun `terminal success settles to a full ring`() {
        val done = running(SubagentTodoProgress(1, 4))
            .copy(status = ActiveSubagent.Status.COMPLETED)
        assertEquals(1f, done.ringFraction)
    }

    @Test
    fun `hasDeterminateProgress is false without todos and true with them or terminal`() {
        assertFalse(running(progress = null).hasDeterminateProgress)
        assertTrue(running(SubagentTodoProgress(0, 3)).hasDeterminateProgress)
        assertTrue(
            running(progress = null)
                .copy(status = ActiveSubagent.Status.COMPLETED)
                .hasDeterminateProgress,
        )
    }

    // ---- BEAD 1: state -> color (ring state) ------------------------------

    @Test
    fun `failed maps to ERROR ring state`() {
        val failed = running().copy(status = ActiveSubagent.Status.FAILED)
        assertEquals(RingState.ERROR, failed.ringState(now = 1_000L))
    }

    @Test
    fun `completed maps to SUCCESS ring state`() {
        val done = running().copy(status = ActiveSubagent.Status.COMPLETED)
        assertEquals(RingState.SUCCESS, done.ringState(now = 1_000L))
    }

    @Test
    fun `running and progressing maps to RUNNING ring state`() {
        // last update just now -> healthy/green.
        val entry = running(lastUpdateAt = 100_000L)
        assertEquals(RingState.RUNNING, entry.ringState(now = 100_000L))
        // still well inside the stuck window.
        assertEquals(
            RingState.RUNNING,
            entry.ringState(now = 100_000L + ActiveSubagent.STUCK_THRESHOLD_MS - 1),
        )
    }

    @Test
    fun `running with no todo change for the threshold maps to STUCK`() {
        val entry = running(lastUpdateAt = 0L)
        assertEquals(
            RingState.STUCK,
            entry.ringState(now = ActiveSubagent.STUCK_THRESHOLD_MS),
        )
        assertEquals(
            RingState.STUCK,
            entry.ringState(now = ActiveSubagent.STUCK_THRESHOLD_MS + 10_000L),
        )
    }

    @Test
    fun `running with unknown last update is never stuck`() {
        // No timing -> we must not false-positive on "stuck".
        val entry = running(lastUpdateAt = null)
        assertEquals(RingState.RUNNING, entry.ringState(now = Long.MAX_VALUE / 2))
    }

    // ---- BEAD 2: copy / state mapping -------------------------------------

    @Test
    fun `running subagent never reads as completed`() {
        val label = running(kind = ActiveSubagent.Kind.SUBAGENT).statusLabel
        assertEquals("Subagent running", label)
        assertFalse(label.lowercase().contains("complete"))
    }

    @Test
    fun `terminal subagent reads as finished only when terminal`() {
        val finished = running().copy(status = ActiveSubagent.Status.COMPLETED)
        assertEquals("Subagent finished", finished.statusLabel)
        val failed = running().copy(status = ActiveSubagent.Status.FAILED)
        assertEquals("Subagent failed", failed.statusLabel)
    }

    @Test
    fun `background task copy is task-flavoured not agent-flavoured`() {
        val bg = running(kind = ActiveSubagent.Kind.BACKGROUND_TASK)
        assertEquals("Background task running", bg.statusLabel)
        assertFalse(bg.statusLabel.lowercase().contains("subagent"))
        val bgDone = bg.copy(status = ActiveSubagent.Status.COMPLETED)
        assertEquals("Background task finished", bgDone.statusLabel)
    }

    @Test
    fun `self plan copy is plan-flavoured`() {
        val self = running(kind = ActiveSubagent.Kind.SELF)
        assertEquals("Your plan", self.statusLabel)
    }

    @Test
    fun `semantic label never says completed for a running entry`() {
        val label = running().chipSemanticLabelForTest()
        assertFalse(label.lowercase().contains("complete"))
    }

    // ---- BEAD 3: unified kind taxonomy ------------------------------------

    @Test
    fun `isSelf implies SELF kind by default`() {
        val self = ActiveSubagent(
            id = ActiveSubagent.SELF_ID,
            description = "Your plan",
            subagentType = "self",
            status = ActiveSubagent.Status.RUNNING,
            isSelf = true,
        )
        assertEquals(ActiveSubagent.Kind.SELF, self.kind)
    }

    @Test
    fun `default kind is SUBAGENT`() {
        assertEquals(ActiveSubagent.Kind.SUBAGENT, running().kind)
    }
}

/**
 * The chip semantic label lives as a private extension in the bar file; this
 * mirrors its contract (state label + description) so the copy guarantee is
 * unit-tested without a Compose harness.
 */
private fun ActiveSubagent.chipSemanticLabelForTest(): String =
    "${statusLabel}: ${description.ifBlank { "(no description)" }}"
