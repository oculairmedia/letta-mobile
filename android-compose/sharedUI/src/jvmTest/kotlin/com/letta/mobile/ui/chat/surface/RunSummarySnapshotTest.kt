@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.timeline.rows.ChatRowTestTags
import com.letta.mobile.ui.theme.LocalReducedMotion
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * letta-mobile-bglj6.1.11: settled runs as the owner reviews them: an older one-command run
 * ("Ran 1 command") and the newest run ("Worked for 1m 7s", four commands, then its narration),
 * at phone width in the Touch idiom and at the desktop docked-panel and full-page widths. Writes
 * PNGs to build/chat-surface-snapshots/run-summary-*.png for review; it asserts only that each
 * rendered.
 */
class RunSummarySnapshotTest {
    private class FixturePort(state: ChatUiState) : ChatSessionPort {
        override val uiState: StateFlow<ChatUiState> = MutableStateFlow(state)
        override val composer: StateFlow<ChatComposerUiState> = MutableStateFlow(ChatComposerUiState())
        override val actions: ChatActions = RecordingChatActions()
    }

    private class Shot(
        val name: String,
        val widthDp: Int,
        val heightDp: Int,
        val scale: Int,
        val presentation: ChatSurfacePresentation,
        val style: ChatPlatformStyle,
        val expandTools: Boolean = false,
    )

    private fun call(id: String, command: String) = UiToolCall(
        name = "Bash",
        arguments = """{"command":"$command"}""",
        result = "ok",
        status = "success",
        toolCallId = id,
    )

    private fun at(seconds: Int): String = "2026-10-03T12:%02d:%02dZ".format(seconds / 60, seconds % 60)

    private val settled = ChatUiState(
        conversationState = ConversationState.Ready("conv-1"),
        messages = listOf(
            UiMessage(id = "u1", role = "user", content = "Can you draw lines between the elements and the notes?", timestamp = at(0)),
            UiMessage(
                id = "r1-a", role = "assistant", content = "", timestamp = at(2), runId = "run-1",
                toolCalls = listOf(call("t1", "git status")),
            ),
            UiMessage(id = "u2", role = "user", content = "?", timestamp = at(20)),
            UiMessage(
                id = "r2-a", role = "assistant", content = "", timestamp = at(22), runId = "run-2",
                toolCalls = listOf(call("t2", "ls"), call("t3", "cat notes.md")),
            ),
            UiMessage(
                id = "r2-b", role = "assistant", content = "", timestamp = at(50), runId = "run-2",
                toolCalls = listOf(call("t4", "./gradlew test"), call("t5", "git diff")),
            ),
            UiMessage(
                id = "r2-c", role = "assistant", content = "Now I'll check that the bodies saved in full, then push.",
                timestamp = at(89), runId = "run-2",
            ),
        ).toPersistentList(),
        isLoadingMessages = false,
        isStreaming = false,
        agentName = "Meridian",
        agentId = "agent-1",
    )

    private fun render(shot: Shot) = runDesktopComposeUiTest(width = shot.widthDp * shot.scale, height = shot.heightDp * shot.scale) {
        mainClock.autoAdvance = false
        val touch = shot.style == ChatPlatformStyle.Touch
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(shot.scale.toFloat(), 1f), LocalReducedMotion provides true) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        ChatSurface(
                            port = FixturePort(settled),
                            presentation = shot.presentation,
                            onIntent = {},
                            host = ChatSurfaceHost(openCanvas = {}),
                            appearance = ChatSurfaceAppearance(
                                toolDetails = if (touch) ChatToolDetails.Sheet else ChatToolDetails.Inline,
                                platformStyle = shot.style,
                            ),
                            canvas = if (shot.presentation == ChatSurfacePresentation.CanvasFirst) canvas else null,
                        )
                    }
                }
            }
        }
        repeat(SETTLE_FRAMES) { mainClock.advanceTimeBy(FRAME_MILLIS) }
        if (shot.expandTools) {
            onNode(hasTestTag(ChatRowTestTags.TOOL_RUN_SUMMARY) and hasText("4 commands", substring = true)).performClick()
            repeat(SETTLE_FRAMES) { mainClock.advanceTimeBy(FRAME_MILLIS) }
        }
        val image = onRoot().captureToImage().toAwtImage()
        val out = File("build/chat-surface-snapshots").apply { mkdirs() }.resolve("${shot.name}.png")
        ImageIO.write(image, "png", out)
        assertTrue(out.length() > 0)
    }

    private val canvas: @Composable (ChatCanvasActions) -> Unit = { _ ->
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh))
    }

    @Test
    fun phone() = render(
        Shot("run-summary-phone", PHONE_WIDTH_DP, PHONE_HEIGHT_DP, scale = 2, ChatSurfacePresentation.ChatFirst, ChatPlatformStyle.Touch),
    )

    @Test
    fun desktopDockedPanel() = render(
        Shot("run-summary-desktop-docked", DESKTOP_WIDTH_DP, DESKTOP_HEIGHT_DP, scale = 1, ChatSurfacePresentation.CanvasFirst, ChatPlatformStyle.Pointer),
    )

    @Test
    fun desktopFullPage() = render(
        Shot("run-summary-desktop-full", DESKTOP_WIDTH_DP, DESKTOP_HEIGHT_DP, scale = 1, ChatSurfacePresentation.ChatFirst, ChatPlatformStyle.Pointer),
    )

    @Test
    fun desktopFullPageExpanded() = render(
        Shot(
            "run-summary-desktop-full-expanded", DESKTOP_WIDTH_DP, DESKTOP_HEIGHT_DP, scale = 1,
            ChatSurfacePresentation.ChatFirst, ChatPlatformStyle.Pointer, expandTools = true,
        ),
    )

    private companion object {
        const val PHONE_WIDTH_DP = 412
        const val PHONE_HEIGHT_DP = 760
        const val DESKTOP_WIDTH_DP = 1280
        const val DESKTOP_HEIGHT_DP = 800
        const val SETTLE_FRAMES = 60
        const val FRAME_MILLIS = 16L
    }
}
