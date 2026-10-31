@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.chat.send.QueuedChatSend
import com.letta.mobile.data.chat.send.QueuedSendId
import kotlin.test.Test

/** Server queue notices wait through brief handoffs; client-held messages remain immediate. */
class QueuedSendsPanelDelayTest {
    /** A send dequeued after half a second never creates a row or an empty panel. */
    @Test
    fun aBriefServerQueueNeverRenders() = runComposeUiTest {
        mainClock.autoAdvance = false
        var queue by mutableStateOf(ConversationSendQueue())
        render { queue }

        runOnIdle { queue = queue.copy(queuedOnServer = send("server")) }
        advanceWithoutServerPanel(500)

        runOnIdle { queue = queue.copy(queuedOnServer = null) }
        mainClock.advanceTimeByFrame()
        advanceWithoutServerPanel(1_500)
    }

    /** A lasting server queue appears after the delay and disappears as soon as it clears. */
    @Test
    fun aLastingServerQueueAppearsAfterTheDelay() = runComposeUiTest {
        mainClock.autoAdvance = false
        var queue by mutableStateOf(ConversationSendQueue(queuedOnServer = send("server")))
        render { queue }

        advanceWithoutServerPanel(999)
        mainClock.advanceTimeBy(501, ignoreFrameDuration = true)
        onNodeWithTag(QueuedSendsPanelTestTags.ON_SERVER).assertIsDisplayed()
        onNodeWithText(QUEUED_ON_SERVER_LABEL).assertIsDisplayed()
        onNodeWithText("1 message queued").assertIsDisplayed()

        runOnIdle { queue = queue.copy(queuedOnServer = null) }
        mainClock.advanceTimeByFrame()
        onNodeWithTag(QueuedSendsPanelTestTags.ON_SERVER).assertDoesNotExist()
        onNodeWithTag(QueuedSendsPanelTestTags.PANEL).assertDoesNotExist()
    }

    /** Client-held sends show their position and header without advancing the clock. */
    @Test
    fun aClientHeldSendAppearsImmediately() = runComposeUiTest {
        mainClock.autoAdvance = false
        render { ConversationSendQueue(items = listOf(send("client"))) }

        onNodeWithTag(QueuedSendsPanelTestTags.ROW + "client").assertIsDisplayed()
        onNodeWithText("Queued · 1").assertIsDisplayed()
        onNodeWithText("1 message queued").assertIsDisplayed()
    }

    /** Only a visible server row contributes to the count alongside client-held sends. */
    @Test
    fun theHeaderCountsOnlyVisibleSends() = runComposeUiTest {
        mainClock.autoAdvance = false
        var queue by mutableStateOf(
            ConversationSendQueue(items = listOf(send("client")), queuedOnServer = send("server")),
        )
        render { queue }

        onNodeWithText("1 message queued").assertIsDisplayed()
        onNodeWithText("2 messages queued").assertDoesNotExist()
        mainClock.advanceTimeBy(1_500)
        onNodeWithText("2 messages queued").assertIsDisplayed()

        runOnIdle { queue = queue.copy(queuedOnServer = null) }
        mainClock.advanceTimeByFrame()
        onNodeWithText("1 message queued").assertIsDisplayed()
        onNodeWithTag(QueuedSendsPanelTestTags.ROW + "client").assertIsDisplayed()
    }

    /** Replacing a pending or visible send starts a fresh delay for the new identity. */
    @Test
    fun aDifferentServerSendRestartsTheDelay() = runComposeUiTest {
        mainClock.autoAdvance = false
        var queue by mutableStateOf(ConversationSendQueue(queuedOnServer = send("first")))
        render { queue }
        advanceWithoutServerPanel(500)

        runOnIdle { queue = queue.copy(queuedOnServer = send("second")) }
        mainClock.advanceTimeByFrame()
        advanceWithoutServerPanel(999)
        mainClock.advanceTimeBy(501, ignoreFrameDuration = true)
        onNodeWithText("second").assertIsDisplayed()

        runOnIdle { queue = queue.copy(queuedOnServer = send("third")) }
        mainClock.advanceTimeByFrame()
        advanceWithoutServerPanel(999)
        mainClock.advanceTimeBy(501, ignoreFrameDuration = true)
        onNodeWithText("third").assertIsDisplayed()
        onNodeWithText("second").assertDoesNotExist()
    }

    private fun ComposeUiTest.render(queue: () -> ConversationSendQueue) {
        setContent { MaterialTheme { QueuedSendsPanel(queue(), QueuedSendActions()) } }
    }

    private fun ComposeUiTest.advanceWithoutServerPanel(durationMillis: Long) {
        val end = mainClock.currentTime + durationMillis
        onNodeWithTag(QueuedSendsPanelTestTags.ON_SERVER).assertDoesNotExist()
        onNodeWithTag(QueuedSendsPanelTestTags.PANEL).assertDoesNotExist()
        while (mainClock.currentTime < end) {
            mainClock.advanceTimeBy(minOf(16, end - mainClock.currentTime), ignoreFrameDuration = true)
            onNodeWithTag(QueuedSendsPanelTestTags.ON_SERVER).assertDoesNotExist()
            onNodeWithTag(QueuedSendsPanelTestTags.PANEL).assertDoesNotExist()
        }
    }

    private fun send(id: String) = QueuedChatSend(QueuedSendId(id), QueueConversationId("conversation"), id)
}
