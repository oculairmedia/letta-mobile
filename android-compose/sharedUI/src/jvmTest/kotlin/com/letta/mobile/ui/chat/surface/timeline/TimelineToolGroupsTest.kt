package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ported from desktop's DesktopToolCallGroupsTest (letta-mobile-bglj6.1). */
class TimelineToolGroupsTest {

    private fun toolMessage(id: String, tool: String = "Bash", timestamp: String = "2026-07-31T01:0$id:00Z") =
        ChatRenderItem.Single(
            message = UiMessage(
                id = id,
                role = "assistant",
                content = "",
                timestamp = timestamp,
                toolCalls = listOf(UiToolCall(name = tool, arguments = "{}", result = null, toolCallId = "tc-$id")),
            ),
            groupPosition = GroupPosition.None,
        )

    private fun doneToolMessage(id: String, tool: String = "Bash", status: String = "success") =
        toolMessage(id, tool).let { single ->
            single.copy(
                message = single.message.copy(
                    toolCalls = single.message.toolCalls.orEmpty().map { it.copy(result = "ok", status = status) },
                ),
            )
        }

    private fun proseMessage(id: String, content: String = "Here is what I found.") =
        ChatRenderItem.Single(
            message = UiMessage(id = id, role = "assistant", content = content, timestamp = "2026-07-31T01:00:00Z"),
            groupPosition = GroupPosition.None,
        )

    @Test
    fun consecutiveToolOnlyMessagesFoldIntoOneGroup() {
        val rows = groupToolCallRows(
            listOf(proseMessage("p1"), toolMessage("1"), toolMessage("2"), toolMessage("3"), proseMessage("p2")),
        )
        assertEquals(3, rows.size)
        val group = rows[1] as TimelineRow.ToolGroup
        assertEquals(3, group.toolCallCount)
        assertEquals(listOf("1", "2", "3"), group.singles.map { it.message.id })
        assertEquals(listOf("Bash" to 3), group.toolNameCounts)
    }

    @Test
    fun loneToolMessageStaysUngrouped() {
        val rows = groupToolCallRows(listOf(proseMessage("p1"), toolMessage("1"), proseMessage("p2")))
        assertTrue(rows.all { it is TimelineRow.Item }, "a group of one is pointless chrome")
    }

    @Test
    fun proseBreaksTheGroup() {
        val rows = groupToolCallRows(
            listOf(toolMessage("1"), toolMessage("2"), proseMessage("p"), toolMessage("3"), toolMessage("4")),
        )
        assertEquals(3, rows.size)
        assertEquals(listOf("1", "2"), (rows[0] as TimelineRow.ToolGroup).singles.map { it.message.id })
        assertTrue(rows[1] is TimelineRow.Item)
        assertEquals(listOf("3", "4"), (rows[2] as TimelineRow.ToolGroup).singles.map { it.message.id })
    }

    @Test
    fun messageWithProseAndToolCallsIsNotFoldable() {
        val withProse = toolMessage("1").let { it.copy(message = it.message.copy(content = "Running the check now.")) }
        assertTrue(groupToolCallRows(listOf(withProse, toolMessage("2"))).all { it is TimelineRow.Item })
    }

    @Test
    fun groupKeyIsStableWhileTheTailGrows() {
        val two = groupToolCallRows(listOf(toolMessage("1"), toolMessage("2"))).single()
        val three = groupToolCallRows(listOf(toolMessage("1"), toolMessage("2"), toolMessage("3"))).single()
        assertEquals(two.key, three.key)
    }

    @Test
    fun boundaryTimestampIsTheNewestMember() {
        val group = groupToolCallRows(
            listOf(toolMessage("1", timestamp = "2026-07-31T01:01:00Z"), toolMessage("2", timestamp = "2026-07-31T01:05:00Z")),
        ).single() as TimelineRow.ToolGroup
        assertEquals("2026-07-31T01:05:00Z", group.boundaryTimestamp)
    }

    @Test
    fun theRowKeySurvivesTheOneToTwoTransition() {
        val one = groupToolCallRows(listOf(toolMessage("1"))).single()
        val two = groupToolCallRows(listOf(toolMessage("1"), toolMessage("2"))).single()
        assertEquals(one.key, two.key)
    }

    @Test
    fun theGroupKeySurvivesTheReversedListToo() {
        // The reversed list folds in chat order, so the key is still the OLDEST member's.
        val newestFirst = listOf(toolMessage("2", timestamp = "2026-07-31T12:02:00Z"), toolMessage("1", timestamp = "2026-07-31T12:01:00Z"))
        val group = timelineRowsNewestFirst(newestFirst).filterIsInstance<TimelineRow.ToolGroup>().single()
        assertEquals(toolMessage("1").key, group.key)
    }

    @Test
    fun adjacentToolMessagesFromDifferentRunsDoNotMerge() {
        val runA = listOf(toolMessage("1"), toolMessage("2")).map { it.copy(stableRunId = "run-a") }
        val runB = listOf(toolMessage("3"), toolMessage("4")).map { it.copy(stableRunId = "run-b") }
        val rows = groupToolCallRows(runA + runB)
        assertEquals(2, rows.size)
        assertEquals(listOf("1", "2"), (rows[0] as TimelineRow.ToolGroup).singles.map { it.message.id })
        assertEquals(listOf("3", "4"), (rows[1] as TimelineRow.ToolGroup).singles.map { it.message.id })
    }

    @Test
    fun aRunBoundaryCanLeaveBothSidesUngrouped() {
        val rows = groupToolCallRows(
            listOf(toolMessage("1").copy(stableRunId = "run-a"), toolMessage("2").copy(stableRunId = "run-b")),
        )
        assertTrue(rows.all { it is TimelineRow.Item }, "one row per run is not a group")
    }

    @Test
    fun finishedSuccessfulCallsFoldAwayCollapsed() {
        val group = groupToolCallRows(listOf(doneToolMessage("1"), doneToolMessage("2"))).single() as TimelineRow.ToolGroup
        assertTrue(!group.startsExpanded)
    }

    @Test
    fun aFailedMemberKeepsTheGroupOpen() {
        val group = groupToolCallRows(
            listOf(doneToolMessage("1"), doneToolMessage("2", status = "error")),
        ).single() as TimelineRow.ToolGroup
        assertTrue(group.startsExpanded)
    }

    @Test
    fun anInFlightMemberKeepsTheGroupOpen() {
        val group = groupToolCallRows(listOf(doneToolMessage("1"), toolMessage("2"))).single() as TimelineRow.ToolGroup
        assertTrue(group.startsExpanded)
    }

    @Test
    fun aGeneratedImageKeepsTheGroupOpen() {
        val withImage = doneToolMessage("2").let { single ->
            single.copy(
                message = single.message.copy(
                    toolCalls = single.message.toolCalls.orEmpty().map {
                        it.copy(generatedImageAttachments = listOf(UiImageAttachment(base64 = "aGVsbG8=", mediaType = "image/png")))
                    },
                ),
            )
        }
        val group = groupToolCallRows(listOf(doneToolMessage("1"), withImage)).single() as TimelineRow.ToolGroup
        assertTrue(group.startsExpanded)
    }

    @Test
    fun runBlocksAndUserMessagesPassThrough() {
        val user = ChatRenderItem.Single(
            message = UiMessage(id = "u1", role = "user", content = "do it", timestamp = "2026-07-31T01:00:00Z"),
            groupPosition = GroupPosition.None,
        )
        val runBlock = ChatRenderItem.RunBlock(
            runId = "run-1",
            messages = listOf(
                UiMessage(id = "r1", role = "assistant", content = "step", timestamp = "2026-07-31T01:00:00Z", runId = "run-1") to
                    GroupPosition.None,
            ),
        )
        val rows = groupToolCallRows(listOf(user, runBlock))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is TimelineRow.Item })
        assertTrue(rows[0].isUserPrompt())
    }
}
