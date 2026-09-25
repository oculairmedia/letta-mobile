package com.letta.mobile.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.chat.send.QueuedChatSend
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.ui.chat.QueuedSendActions
import com.letta.mobile.ui.chat.QueuedSendsPanel
import com.letta.mobile.ui.chat.QueuedSendsPanelTestTags
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** letta-mobile-1n5py: queued messages render in order with their controls. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class QueuedSendsPanelTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val cancelled = mutableListOf<String>()
    private val sentNow = mutableListOf<String>()
    private var resumed = 0

    private fun render(queue: ConversationSendQueue) {
        composeRule.setContent {
            LettaTheme {
                QueuedSendsPanel(
                    queue = queue,
                    actions = QueuedSendActions(
                        onCancel = { cancelled += it.value },
                        onSendNow = { sentNow += it.value },
                        onResume = { resumed += 1 },
                    ),
                )
            }
        }
    }

    @Test
    fun `each queued message shows its place in line`() {
        render(ConversationSendQueue(items = listOf(send("a", "first"), send("b", "second"), send("c", "third"))))

        composeRule.onNodeWithText("3 messages queued").assertIsDisplayed()
        composeRule.onNodeWithText("Queued · 1").assertIsDisplayed()
        composeRule.onNodeWithText("Queued · 2").assertIsDisplayed()
        composeRule.onNodeWithText("Queued · 3").assertIsDisplayed()
        composeRule.onNodeWithText("second").assertIsDisplayed()
        composeRule.onNodeWithTag(QueuedSendsPanelTestTags.RESUME).assertDoesNotExist()
    }

    @Test
    fun `row controls cancel or push through that message only`() {
        render(ConversationSendQueue(items = listOf(send("a", "first"), send("b", "second"))))

        composeRule.onNodeWithTag(QueuedSendsPanelTestTags.CANCEL + "b").performClick()
        composeRule.onNodeWithTag(QueuedSendsPanelTestTags.SEND_NOW + "a").performClick()

        assertEquals(listOf("b"), cancelled)
        assertEquals(listOf("a"), sentNow)
    }

    @Test
    fun `a queue held by a stop offers resume`() {
        render(ConversationSendQueue(items = listOf(send("a", "first")), paused = true))

        composeRule.onNodeWithText("Queue paused · 1 message").assertIsDisplayed()
        composeRule.onNodeWithTag(QueuedSendsPanelTestTags.RESUME).performClick()

        assertEquals(1, resumed)
    }

    @Test
    fun `nothing renders when nothing is queued`() {
        render(ConversationSendQueue())

        composeRule.onNodeWithTag(QueuedSendsPanelTestTags.PANEL).assertDoesNotExist()
        assertTrue(cancelled.isEmpty())
    }

    private fun send(id: String, text: String) =
        QueuedChatSend(id = QueuedSendId(id), conversationId = QueueConversationId("conv-1"), text = text)
}
