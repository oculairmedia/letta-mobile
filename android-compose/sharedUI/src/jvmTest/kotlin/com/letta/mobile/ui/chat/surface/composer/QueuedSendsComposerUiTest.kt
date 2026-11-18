@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.chat.send.QueuedChatSend
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.ui.chat.QueuedSendsPanelTestTags
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The queued-message row in the composer: docked one small gap above the prompt (the card or the
 * Touch bar), and its Edit takes the message back into the draft (after what is typed) and out of the
 * queue, while Send now and Remove go to the session's own queue actions.
 */
class QueuedSendsComposerUiTest {
    private val actions = RecordingChatActions()
    private val queue = ConversationSendQueue(
        items = listOf(
            QueuedChatSend(QueuedSendId("a"), QueueConversationId("c1"), text = "Then run the release checklist"),
            QueuedChatSend(QueuedSendId("b"), QueueConversationId("c1"), text = "And tag the build"),
        ),
    )

    private fun ComposeUiTest.show(width: Dp, style: LettaInput, draft: String = "") = setContent {
        MaterialTheme {
            CompositionLocalProvider(LocalLettaHost provides LettaHost(input = style)) {
                Box(Modifier.width(width).height(PAGE_HEIGHT), contentAlignment = Alignment.BottomCenter) {
                    ChatComposerPanel(
                        ComposerInputs(
                            composer = ChatComposerUiState(
                                text = draft,
                                permissionMode = ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted),
                            ),
                            actions = actions,
                            uiState = ChatUiState(sendQueue = queue),
                        ),
                    )
                }
            }
        }
    }

    private fun docksAboveThePrompt(width: Dp, style: LettaInput, bar: String) = runComposeUiTest {
        show(width, style)
        waitForIdle()
        val row = onNodeWithTag(QueuedSendsPanelTestTags.PANEL).getUnclippedBoundsInRoot()
        val prompt = onNodeWithTag(bar, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(row.bottom <= prompt.top, "the row sits above the prompt at $width ($style)")
        assertTrue(prompt.top - row.bottom <= 16.dp, "one small gap between them at $width ($style)")
    }

    @Test
    fun theRowDocksAboveTheCardAt1200() = docksAboveThePrompt(1200.dp, LettaInput.Pointer, ComposerTestTags.CARD)

    @Test
    fun theRowDocksAboveTheCardAt760() = docksAboveThePrompt(760.dp, LettaInput.Pointer, ComposerTestTags.CARD)

    @Test
    fun theRowDocksAboveTheTouchBarAt700() = docksAboveThePrompt(700.dp, LettaInput.Touch, ComposerTestTags.TOUCH_BAR)

    @Test
    fun theRowDocksAboveTheTouchBarAt360() = docksAboveThePrompt(360.dp, LettaInput.Touch, ComposerTestTags.TOUCH_BAR)

    @Test
    fun editTakesTheMessageBackIntoTheDraftAndOutOfTheQueue() = runComposeUiTest {
        show(1200.dp, LettaInput.Pointer, draft = "Before that,")
        onNodeWithTag(QueuedSendsPanelTestTags.EDIT + "a").performClick()
        waitForIdle()
        assertEquals(listOf("cancelQueuedSend:a"), actions.calls.filter { it.startsWith("cancelQueuedSend") })
        assertEquals("Before that,\nThen run the release checklist", actions.texts.last())
    }

    @Test
    fun sendNowAndRemoveGoToTheQueuesOwnActions() = runComposeUiTest {
        show(760.dp, LettaInput.Pointer)
        onNodeWithTag(QueuedSendsPanelTestTags.SEND_NOW + "a").performClick()
        onNodeWithTag(QueuedSendsPanelTestTags.MORE).performClick()
        waitForIdle()
        onNodeWithTag(QueuedSendsPanelTestTags.CANCEL + "b").performClick()
        waitForIdle()
        assertTrue("sendQueuedNow:a" in actions.calls, "${actions.calls}")
        assertTrue("cancelQueuedSend:b" in actions.calls, "${actions.calls}")
    }

    private companion object {
        val PAGE_HEIGHT = 900.dp
    }
}
