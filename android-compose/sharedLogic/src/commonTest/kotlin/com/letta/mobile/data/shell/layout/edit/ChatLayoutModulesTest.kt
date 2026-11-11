package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Independent wire-level contract for the chat composition. */
class ChatLayoutModulesTest {
    private fun slot(view: String) = ModuleSlot(RuntimeViewId(view))
    private val expected = listOf("chat.transcript", "chat.status", "chat.composer").map(::slot)

    @Test
    fun defaultOrderPreservesTheExistingPage() {
        assertEquals(expected, ShellLayoutConfig.Default.lane("chat"))
    }

    @Test
    fun transcriptAndComposerCannotBeRemoved() {
        listOf("chat.transcript", "chat.composer").forEach { view ->
            val result = assertIs<ShellLayoutEditResult.Rejected>(
                ShellLayoutEdits.apply(ShellLayoutConfig.Default, listOf(ShellLayoutOp.Remove("chat", slot(view)))),
            )
            assertTrue(result.errors.any { it.rule.code == "chat_read_send_stays" })
        }
    }

    @Test
    fun reorderHideAndInsertCanvasAboveTranscript() {
        val result = assertIs<ShellLayoutEditResult.Applied>(
            ShellLayoutEdits.apply(
                ShellLayoutConfig.Default,
                listOf(
                    ShellLayoutOp.Move("chat", slot("chat.composer"), index = 0),
                    ShellLayoutOp.Remove("chat", slot("chat.status")),
                    ShellLayoutOp.Add("chat", ModuleSlot(ShellViews.CANVAS, "board-a"), index = 1),
                ),
            ),
        )
        assertEquals(
            listOf(slot("chat.composer"), ModuleSlot(ShellViews.CANVAS, "board-a"), slot("chat.transcript")),
            result.after.lane("chat"),
        )
    }

    @Test
    fun upgradeAddsChatWithoutRestoringEarlierRemovals() {
        val old = ShellLayoutConfig(version = ShellLayoutConfig.VERSION - 1, lanes = mapOf("home" to emptyList()))
        val upgraded = ShellLayoutConfig.decode(old.encode())
        assertEquals(emptyList(), upgraded.lane("home"))
        assertEquals(expected, upgraded.lane("chat"))
        val removed = upgraded.copy(lanes = upgraded.lanes + ("chat" to expected.filterNot { it.view.value == "chat.status" }))
        assertEquals(removed, ShellLayoutConfig.decode(removed.encode()))
    }
}
