package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopToolCallGroupsTest {

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

    private fun proseMessage(id: String, content: String = "Here is what I found.") =
        ChatRenderItem.Single(
            message = UiMessage(id = id, role = "assistant", content = content, timestamp = "2026-07-31T01:00:00Z"),
            groupPosition = GroupPosition.None,
        )

    @Test
    fun consecutiveToolOnlyMessagesFoldIntoOneGroup() {
        // The reported clutter: six near-identical Bash cards in a row.
        val rows = groupDesktopChatRows(
            listOf(proseMessage("p1"), toolMessage("1"), toolMessage("2"), toolMessage("3"), proseMessage("p2")),
        )
        assertEquals(3, rows.size)
        val group = rows[1] as DesktopChatRow.ToolGroup
        assertEquals(3, group.toolCallCount)
        assertEquals(listOf("1", "2", "3"), group.singles.map { it.message.id })
    }

    @Test
    fun loneToolMessageStaysUngrouped() {
        val rows = groupDesktopChatRows(listOf(proseMessage("p1"), toolMessage("1"), proseMessage("p2")))
        assertTrue(rows.all { it is DesktopChatRow.Item }, "a group of one is pointless chrome")
    }

    @Test
    fun proseBreaksTheGroup() {
        // Prose between tool calls is conversation — it must never fold away.
        val rows = groupDesktopChatRows(
            listOf(toolMessage("1"), toolMessage("2"), proseMessage("p"), toolMessage("3"), toolMessage("4")),
        )
        assertEquals(3, rows.size)
        assertEquals(listOf("1", "2"), (rows[0] as DesktopChatRow.ToolGroup).singles.map { it.message.id })
        assertTrue(rows[1] is DesktopChatRow.Item)
        assertEquals(listOf("3", "4"), (rows[2] as DesktopChatRow.ToolGroup).singles.map { it.message.id })
    }

    @Test
    fun messageWithProseAndToolCallsIsNotFoldable() {
        // A message carrying BOTH text and tool calls renders its prose — only
        // pure-mechanics messages fold.
        val withProse = toolMessage("1").let {
            it.copy(message = it.message.copy(content = "Running the check now."))
        }
        val rows = groupDesktopChatRows(listOf(withProse, toolMessage("2")))
        assertTrue(rows.all { it is DesktopChatRow.Item })
    }

    @Test
    fun groupKeyIsStableWhileTheTailGrows() {
        // Streaming appends tool calls at the tail; the group is keyed off its
        // FIRST member so the LazyColumn slot survives the growth.
        val twoRows = groupDesktopChatRows(listOf(toolMessage("1"), toolMessage("2")))
        val threeRows = groupDesktopChatRows(listOf(toolMessage("1"), toolMessage("2"), toolMessage("3")))
        assertEquals(
            (twoRows.single() as DesktopChatRow.ToolGroup).key,
            (threeRows.single() as DesktopChatRow.ToolGroup).key,
        )
    }

    @Test
    fun boundaryTimestampIsTheNewestMember() {
        val group = groupDesktopChatRows(
            listOf(toolMessage("1", timestamp = "2026-07-31T01:01:00Z"), toolMessage("2", timestamp = "2026-07-31T01:05:00Z")),
        ).single() as DesktopChatRow.ToolGroup
        assertEquals("2026-07-31T01:05:00Z", group.boundaryTimestamp)
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
                UiMessage(id = "r1", role = "assistant", content = "step", timestamp = "2026-07-31T01:00:00Z", runId = "run-1") to GroupPosition.None,
            ),
        )
        val rows = groupDesktopChatRows(listOf(user, runBlock))
        assertEquals(2, rows.size)
        assertTrue(rows.all { it is DesktopChatRow.Item })
    }
}
