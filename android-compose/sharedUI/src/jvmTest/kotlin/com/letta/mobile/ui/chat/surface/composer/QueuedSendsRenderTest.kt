@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
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
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The queued-message row docked above the composer at the review widths (360, 700, 760, 1200 dp) in
 * Dark and Light, written to build/queued-row-render/ for review: one message queued, and three
 * (opened, the rest under the row). Neutral demo text only.
 */
class QueuedSendsRenderTest {
    private fun send(id: String, text: String) = QueuedChatSend(QueuedSendId(id), QueueConversationId("c1"), text = text)

    private val one = ConversationSendQueue(items = listOf(send("a", "Then run the release checklist and tag the build")))
    private val three = ConversationSendQueue(
        items = listOf(
            send("a", "Then run the release checklist and tag the build"),
            send("b", "Draft the changelog from the merged pull requests"),
            send("c", "Post a short summary in the team channel"),
        ),
    )

    @Test
    fun theRowRendersAtEveryReviewWidthInBothThemes() {
        for (dark in listOf(true, false)) {
            for (width in WIDTHS) {
                render("one-$width-${theme(dark)}.png", width, dark, one, open = false)
                render("three-$width-${theme(dark)}.png", width, dark, three, open = true)
            }
        }
    }

    private fun render(name: String, width: Int, dark: Boolean, queue: ConversationSendQueue, open: Boolean) =
        runDesktopComposeUiTest(width = width, height = HEIGHT) {
            val touch = width < LettaDimens.Pane.wideBreakpoint.value
            setContent { Page(queue, touch, dark) }
            waitForIdle()
            if (open) onNodeWithTag(QueuedSendsPanelTestTags.MORE).performClick()
            waitForIdle()
            val file = File("build/queued-row-render").apply { mkdirs() }.resolve(name)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        }

    @Composable
    private fun Page(queue: ConversationSendQueue, touch: Boolean, dark: Boolean) {
        SharedMaterialTheme(darkTheme = dark) {
            ProvideLettaHost(if (touch) LettaHost.Touch else LettaHost.Pointer) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        ChatComposerPanel(
                            ComposerInputs(
                                composer = ChatComposerUiState(
                                    text = "",
                                    permissionMode = ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted),
                                ),
                                actions = RecordingChatActions(),
                                uiState = ChatUiState(sendQueue = queue),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"

    private companion object {
        val WIDTHS = listOf(360, 700, 760, 1200)
        const val HEIGHT = 360
    }
}
