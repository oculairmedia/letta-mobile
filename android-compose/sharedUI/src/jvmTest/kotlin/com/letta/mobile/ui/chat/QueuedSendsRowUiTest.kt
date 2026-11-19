@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.chat.send.QueuedChatSend
import com.letta.mobile.data.chat.send.QueuedSendId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The queued-message row: "1 message queued" with the next message's first line and three labelled
 * buttons for it (send now, edit, remove); with more waiting, a "+N" that opens the rest with the same
 * buttons; Edit disabled where the host cannot take a message back.
 */
class QueuedSendsRowUiTest {
    private val cancelled = mutableListOf<String>()
    private val sentNow = mutableListOf<String>()
    private val edited = mutableListOf<String>()

    private fun send(id: String, text: String) =
        QueuedChatSend(id = QueuedSendId(id), conversationId = QueueConversationId("conv-1"), text = text)

    private fun ComposeUiTest.show(queue: ConversationSendQueue, editable: Boolean = true) = setContent {
        MaterialTheme {
            QueuedSendsPanel(
                queue = queue,
                actions = QueuedSendActions(
                    onCancel = { cancelled += it.value },
                    onSendNow = { sentNow += it.value },
                    onEdit = if (editable) ({ item -> edited += item.id.value }) else null,
                ),
            )
        }
    }

    private val button = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    @Test
    fun oneQueuedMessageIsOneRowWithItsThreeButtons() = runComposeUiTest {
        show(ConversationSendQueue(items = listOf(send("a", "Check the nightly build\nand then the tests"))))
        onNodeWithText("1 message queued").assertExists()
        // The first line only, dimmed beside the count.
        onNodeWithText("Check the nightly build").assertExists()
        onNodeWithTag(QueuedSendsPanelTestTags.MORE).assertDoesNotExist()

        onNodeWithTag(QueuedSendsPanelTestTags.SEND_NOW + "a")
            .assertContentDescriptionEquals("Send queued message now")
            .assert(button)
            .performClick()
        onNodeWithTag(QueuedSendsPanelTestTags.EDIT + "a").assertContentDescriptionEquals("Edit queued message").performClick()
        onNodeWithTag(QueuedSendsPanelTestTags.CANCEL + "a").assertContentDescriptionEquals("Remove queued message").performClick()
        waitForIdle()
        assertEquals(listOf("a"), sentNow)
        assertEquals(listOf("a"), edited)
        assertEquals(listOf("a"), cancelled)
    }

    @Test
    fun threeQueuedOpenTheRestUnderTheRowWithTheSameButtons() = runComposeUiTest {
        show(ConversationSendQueue(items = listOf(send("a", "first"), send("b", "second"), send("c", "third"))))
        onNodeWithText("3 messages queued").assertExists()
        onNodeWithText("second").assertDoesNotExist()
        onNodeWithTag(QueuedSendsPanelTestTags.MORE)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()
        waitForIdle()
        onNodeWithTag(QueuedSendsPanelTestTags.MORE)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
        onNodeWithText("Queued · 2").assertExists()
        onNodeWithText("third").assertExists()
        onNodeWithTag(QueuedSendsPanelTestTags.SEND_NOW + "c").performClick()
        onNodeWithTag(QueuedSendsPanelTestTags.EDIT + "b").performClick()
        onNodeWithTag(QueuedSendsPanelTestTags.CANCEL + "b").performClick()
        waitForIdle()
        assertEquals(listOf("c"), sentNow)
        assertEquals(listOf("b"), edited)
        assertEquals(listOf("b"), cancelled)
    }

    @Test
    fun editIsDisabledWhereTheHostCannotTakeAMessageBack() = runComposeUiTest {
        show(ConversationSendQueue(items = listOf(send("a", "first"))), editable = false)
        onNodeWithTag(QueuedSendsPanelTestTags.EDIT + "a").assertIsNotEnabled()
    }
}
