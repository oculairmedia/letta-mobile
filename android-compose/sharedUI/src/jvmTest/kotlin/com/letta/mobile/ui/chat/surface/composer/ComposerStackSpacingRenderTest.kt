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
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.chat.send.ConversationSendQueue
import com.letta.mobile.data.chat.send.QueueConversationId
import com.letta.mobile.data.chat.send.QueuedChatSend
import com.letta.mobile.data.chat.send.QueuedSendId
import com.letta.mobile.data.transport.appserver.AppServerBackgroundProcess
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlinx.collections.immutable.toImmutableList

/**
 * The stack above the prompt bar (the background-process chips, the queued-message row, both) at the
 * review widths (360, 700, 760, 1200 dp) in Dark and Light, on the full page and the canvas dock,
 * written to build/composer-stack-render/ for review. Neutral demo data only.
 */
class ComposerStackSpacingRenderTest {
    private val processes = listOf(
        AppServerBackgroundProcess(id = "bash_1048", type = "bash", description = "bash_1048"),
        AppServerBackgroundProcess(id = "task_7", type = "agent_task", description = "Review the release notes"),
    )
    private val queue = ConversationSendQueue(
        items = listOf(QueuedChatSend(QueuedSendId("a"), QueueConversationId("c1"), text = "Then run the release checklist")),
    )

    @Test
    fun theStackRendersAtEveryReviewWidthInBothThemes() {
        val modes = listOf(ChatSurfaceMode.FullScreen, ChatSurfaceMode.Docked)
        for (dark in listOf(true, false)) {
            for (width in WIDTHS) modes.forEach { mode -> renderCases(width, dark, mode) }
        }
    }

    private fun renderCases(width: Int, dark: Boolean, mode: ChatSurfaceMode) {
        val tag = "$width-${if (mode == ChatSurfaceMode.Docked) "dock" else "page"}-${if (dark) "dark" else "light"}"
        render("chips-$tag.png", width, dark, mode, processes, ConversationSendQueue())
        render("queued-$tag.png", width, dark, mode, emptyList(), queue)
        render("both-$tag.png", width, dark, mode, processes, queue)
    }

    private fun render(
        name: String,
        width: Int,
        dark: Boolean,
        mode: ChatSurfaceMode,
        processes: List<AppServerBackgroundProcess>,
        queue: ConversationSendQueue,
    ) = runDesktopComposeUiTest(width = width, height = HEIGHT) {
        val touch = width < LettaDimens.Pane.wideBreakpoint.value
        setContent { Page(touch, dark, mode, processes, queue) }
        waitForIdle()
        val file = File("build/composer-stack-render").apply { mkdirs() }.resolve(name)
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    @Composable
    private fun Page(
        touch: Boolean,
        dark: Boolean,
        mode: ChatSurfaceMode,
        processes: List<AppServerBackgroundProcess>,
        queue: ConversationSendQueue,
    ) {
        SharedMaterialTheme(darkTheme = dark) {
            ProvideLettaHost(if (touch) LettaHost.Touch else LettaHost.Pointer) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        ChatComposerPanel(
                            ComposerInputs(
                                composer = ChatComposerUiState(text = "", backgroundProcesses = processes.toImmutableList()),
                                actions = RecordingChatActions(),
                                uiState = ChatUiState(sendQueue = queue),
                                mode = mode,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }

    private companion object {
        val WIDTHS = listOf(360, 700, 760, 1200)
        const val HEIGHT = 300
    }
}
