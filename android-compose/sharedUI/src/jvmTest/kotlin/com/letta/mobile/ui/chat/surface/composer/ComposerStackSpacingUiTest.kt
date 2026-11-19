@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.chat.send.QueuedChatSend
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.transport.appserver.AppServerBackgroundProcess
import com.letta.mobile.ui.chat.QueuedSendsPanelTestTags
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.SharedMaterialTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf

/**
 * Every row stacked above the prompt bar keeps the stack's one gap ([LettaDimens.Space.sm]) to what
 * sits below it: the background-process chips to the queued row, or to the bar when nothing is
 * queued; the queued row to the bar. On the pointer card, the Touch bar and the canvas dock.
 */
class ComposerStackSpacingUiTest {
    private val processes = persistentListOf(AppServerBackgroundProcess(id = "bash_1048", type = "bash", description = "bash_1048"))
    private val queue = ConversationSendQueue(
        items = listOf(QueuedChatSend(QueuedSendId("a"), QueueConversationId("c1"), text = "Then run the release checklist")),
    )

    @Test
    fun theChipsKeepTheStackGapToTheQueuedRowAndTheQueuedRowToTheBar() {
        for (case in CASES) {
            assertGaps(case, queued = true)
            assertGaps(case, queued = false)
        }
    }

    private fun assertGaps(case: Case, queued: Boolean) = runDesktopComposeUiTest(width = case.width, height = HEIGHT) {
        setContent {
            SharedMaterialTheme(darkTheme = true) {
                ProvideLettaHost(case.host) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            ChatComposerPanel(
                                ComposerInputs(
                                    composer = ChatComposerUiState(text = "", backgroundProcesses = processes),
                                    actions = RecordingChatActions(),
                                    uiState = ChatUiState(sendQueue = if (queued) queue else ConversationSendQueue()),
                                    mode = case.mode,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        waitForIdle()
        val chip = onNodeWithTag("process_item_bash_1048", useUnmergedTree = true).getBoundsInRoot()
        val bar = onNodeWithTag(case.barTag, useUnmergedTree = true).getBoundsInRoot()
        val label = "${case.name}, queued=$queued"
        if (queued) {
            val row = onNodeWithTag(QueuedSendsPanelTestTags.PANEL, useUnmergedTree = true).getBoundsInRoot()
            assertGap("$label: chips to queued row", chip, row.top)
            assertGap("$label: queued row to bar", row, bar.top)
        } else {
            assertGap("$label: chips to bar", chip, bar.top)
        }
    }

    private fun assertGap(label: String, above: DpRect, belowTop: Dp) {
        val gap = belowTop - above.bottom
        assertTrue(abs((gap - LettaDimens.Space.sm).value) <= TOLERANCE_DP, "$label: gap $gap, expected ${LettaDimens.Space.sm}")
    }

    private class Case(val name: String, val width: Int, val host: LettaHost, val mode: ChatSurfaceMode, val barTag: String)

    private companion object {
        const val HEIGHT = 300
        const val TOLERANCE_DP = 1f
        val CASES = listOf(
            Case("pointer card", 1200, LettaHost.Pointer, ChatSurfaceMode.FullScreen, ComposerTestTags.CARD),
            Case("touch bar", 360, LettaHost.Touch, ChatSurfaceMode.FullScreen, ComposerTestTags.TOUCH_BAR),
            Case("pointer dock", 1200, LettaHost.Pointer, ChatSurfaceMode.Docked, ComposerTestTags.DOCKED_BAR),
            Case("touch dock", 360, LettaHost.Touch, ChatSurfaceMode.Docked, ComposerTestTags.TOUCH_BAR),
        )
    }
}
