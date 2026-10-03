package com.letta.mobile.data.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-jqiu3 (AC2, projection half): a model turn segmented around its tool calls yields
 * whitespace-only text segments. Those segments must not become render items on either the live
 * or the settled (cold ledger) path, and dropping them must not split the turn's run, which would
 * scatter its tool calls across rows instead of one collapsed group.
 *
 * The transcript goes through the same LocalBackend -> wire -> canonical engine route as
 * [ExactToolTurnsTest], so the settled rows are the ones the paged timeline presents.
 */
class ContentlessSegmentProjectionTest {
    @Test fun whitespaceSegmentsBecomeNoRenderItemsOnTheSettledPath() = runTest {
        val projected = projectTurn()

        assertNoContentlessRows(projected.settled)
        assertEquals(listOf(4), toolCallsPerRun(projected.settled), "the run must stay one aggregate")
        assertEquals(TURN_TEXT, visibleText(projected.settled))
    }

    @Test fun whitespaceSegmentsBecomeNoRenderItemsOnTheLivePath() = runTest {
        val projected = projectTurn()

        assertNoContentlessRows(projected.live)
        assertEquals(listOf(4), toolCallsPerRun(projected.live))
        assertEquals(TURN_TEXT, visibleText(projected.live))
    }

    private suspend fun projectTurn() =
        LocalBackendTurnHarness("jqiu3").project(LocalBackendTurnHarness.wireMessages(realShapedTurn()))

    private fun assertNoContentlessRows(items: List<ChatRenderItem>) {
        val blank = items.flatMap { it.members() }.filter { it.isAssistantWithoutContent() }
        assertTrue(blank.isEmpty(), "zero-content render members: ${blank.map { it.id }}")
    }

    private fun UiMessage.isAssistantWithoutContent() =
        role == "assistant" && !isReasoning && content.isBlank() && toolCalls.isNullOrEmpty()

    private fun toolCallsPerRun(items: List<ChatRenderItem>) = items.filterIsInstance<ChatRenderItem.RunBlock>()
        .map { block -> block.messages.sumOf { it.first.toolCalls.orEmpty().size } }

    private fun visibleText(items: List<ChatRenderItem>) = items.flatMap { it.members() }
        .filter { it.role == "assistant" && !it.isReasoning && it.content.isNotBlank() }
        .map { it.content }
        .toSet()

    /** text, tool card, whitespace, tool card, reasoning, whitespace, tool card, text. */
    private fun realShapedTurn(): List<TurnEntry> = listOf(
        TurnEntry.User("u1", "please fix the spacing"),
        TurnEntry.Assistant("a1", listOf(TurnPart.Text("I'll inspect the files."), TurnPart.Call("c1", "Edit"), TurnPart.Call("c2", "Edit"))),
        TurnEntry.ToolResult("r1", "c1", "Edit"),
        TurnEntry.ToolResult("r2", "c2", "Edit"),
        TurnEntry.Assistant("a2", listOf(TurnPart.Text("\n\n"), TurnPart.Call("c3", "Bash"))),
        TurnEntry.ToolResult("r3", "c3", "Bash"),
        TurnEntry.Assistant("a3", listOf(TurnPart.Reasoning("Verify the edit"))),
        TurnEntry.Assistant("a4", listOf(TurnPart.Text(" "), TurnPart.Call("c4", "Bash"))),
        TurnEntry.ToolResult("r4", "c4", "Bash"),
        TurnEntry.Assistant("a5", listOf(TurnPart.Text("All fixed."))),
    )

    private companion object {
        val TURN_TEXT = setOf("I'll inspect the files.", "All fixed.")
    }
}
