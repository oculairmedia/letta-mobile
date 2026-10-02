@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.canvas.compose.ComposeBounds
import com.letta.mobile.data.canvas.compose.ComposeKind
import com.letta.mobile.data.chat.projection.CanvasArtifactError
import com.letta.mobile.data.chat.projection.CanvasArtifactReceipt
import com.letta.mobile.data.chat.projection.CanvasArtifactStatus
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.mascot.FakeMascotHost
import com.letta.mobile.ui.mascot.FakeMascotShell
import com.letta.mobile.ui.mascot.LocalMascotHost
import com.letta.mobile.ui.mascot.MascotEntry
import com.letta.mobile.ui.mascot.MascotHost
import com.letta.mobile.ui.mascot.MascotTransportLayer
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * letta-mobile-bglj6.13: the canvas_compose cards on the full-screen page, at a desktop size in the
 * Pointer idiom and at phone size (412 x 915 dp, drawn at 2x) in the Touch idiom, light and dark:
 * a published artifact on its narration, one still being added, and a refused one. Writes PNGs to
 * build/chat-surface-snapshots/canvas-artifact-*.png for a reviewer; it asserts only that each
 * rendered.
 */
class ChatCanvasArtifactSnapshotTest {
    private class FixturePort(state: ChatUiState) : ChatSessionPort {
        override val uiState: StateFlow<ChatUiState> = MutableStateFlow(state)
        override val composer: StateFlow<ChatComposerUiState> = MutableStateFlow(ChatComposerUiState(canSend = true))
        override val actions: ChatActions = RecordingChatActions()
    }

    /** What a card names: its artifact, title, kinds and piece count. */
    private data class Card(val id: String, val title: String, val kinds: List<ComposeKind>, val count: Int)

    private fun receipt(card: Card, status: CanvasArtifactStatus, error: CanvasArtifactError? = null) = CanvasArtifactReceipt(
        artifactId = card.id,
        canvasId = "canvas-conversation-conv-1",
        revision = 42,
        status = status,
        title = card.title,
        kinds = card.kinds,
        itemCount = card.count,
        bounds = if (status == CanvasArtifactStatus.Published) ComposeBounds(80f, 80f, 712f, 746f) else null,
        error = error,
    )

    private val composeCall = UiToolCall(
        name = "canvas_compose", arguments = "{\"title\":\"Weekend plan\"}", result = "{\"ok\":true}", status = "success", toolCallId = "t1",
    )

    private val state = ChatUiState(
        conversationState = ConversationState.Ready("conv-1"),
        isLoadingMessages = false,
        agentName = "Meridian",
        agentId = "agent-1",
        messages = persistentListOf(
            UiMessage(id = "u1", role = "user", content = "Plan my weekend on the board.", timestamp = "2026-10-01T10:00:00Z"),
            UiMessage(
                id = "tc1", role = "assistant", content = "", timestamp = "2026-10-01T10:00:04Z", runId = "run-1",
                toolCalls = listOf(composeCall),
            ),
            UiMessage(
                id = "a1", role = "assistant", content = "I put the plan on the board: a shopping checklist, meals, and a self-care group.",
                timestamp = "2026-10-01T10:00:06Z", runId = "run-1",
                artifacts = listOf(
                    receipt(
                        Card(
                            "weekend-plan", "Weekend plan",
                            listOf(ComposeKind.TEXT, ComposeKind.CHECKLIST, ComposeKind.NOTE, ComposeKind.GROUP, ComposeKind.CARD), 6,
                        ),
                        CanvasArtifactStatus.Published,
                    ),
                ),
            ),
            UiMessage(id = "u2", role = "user", content = "Add a packing list and a budget.", timestamp = "2026-10-01T10:01:00Z"),
            UiMessage(
                id = "a2", role = "assistant", content = "Adding a packing list now.", timestamp = "2026-10-01T10:01:03Z", runId = "run-2",
                artifacts = listOf(receipt(Card("packing", "Packing list", listOf(ComposeKind.CHECKLIST), 1), CanvasArtifactStatus.Pending)),
            ),
            UiMessage(
                id = "a3", role = "assistant", content = "The budget did not go on the board.", timestamp = "2026-10-01T10:01:05Z", runId = "run-2",
                artifacts = listOf(
                    receipt(
                        Card("budget", "Budget", listOf(ComposeKind.CARD), 1), CanvasArtifactStatus.Failed,
                        error = CanvasArtifactError("VALIDATION_FAILED", "a CARD holds at most 8 fields (got 11)", problemCount = 2),
                    ),
                ),
            ),
        ),
    )

    private class Shot(val name: String, val phone: Boolean, val dark: Boolean)

    private fun snapshot(shot: Shot) {
        val width = if (shot.phone) PHONE_WIDTH_DP * SCALE else DESKTOP_WIDTH
        val height = if (shot.phone) PHONE_HEIGHT_DP * SCALE else DESKTOP_HEIGHT
        runDesktopComposeUiTest(width = width, height = height) {
            // The mascot's layer keeps a frame loop running, so the page never idles: step the clock.
            mainClock.autoAdvance = false
            setContent {
                val density = if (shot.phone) Density(SCALE.toFloat(), 1f) else LocalDensity.current
                CompositionLocalProvider(LocalDensity provides density) {
                    MaterialTheme(colorScheme = if (shot.dark) darkColorScheme() else lightColorScheme()) {
                        WithMascot {
                            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                ChatSurface(
                                    port = FixturePort(state),
                                    presentation = ChatSurfacePresentation.ChatFirst,
                                    onIntent = {},
                                    host = ChatSurfaceHost(openCanvas = {}, showOnCanvas = {}),
                                    appearance = if (shot.phone) {
                                        ChatSurfaceAppearance(platformStyle = ChatPlatformStyle.Touch, toolDetails = ChatToolDetails.Sheet)
                                    } else {
                                        ChatSurfaceAppearance()
                                    },
                                    platform = ChatSurfacePlatform(showKeyboardHints = !shot.phone),
                                )
                            }
                        }
                    }
                }
            }
            repeat(SETTLE_FRAMES) { mainClock.advanceTimeBy(FRAME_MILLIS) }
            val image = onRoot().captureToImage().toAwtImage()
            val out = File("build/chat-surface-snapshots").apply { mkdirs() }.resolve("${shot.name}.png")
            ImageIO.write(image, "png", out)
            assertTrue(out.length() > 0)
        }
    }

    @Composable
    private fun WithMascot(content: @Composable () -> Unit) {
        val shell = FakeMascotShell("agent-1")
        shell.Provide {
            CompositionLocalProvider(LocalMascotHost provides StandInMascotHost) {
                MascotTransportLayer { content() }
            }
        }
    }

    private object StandInMascotHost : MascotHost {
        override val available: Boolean = true

        override fun entry(agentId: String, identity: MascotIdentity): MascotEntry = FakeMascotHost.entry(agentId, identity)

        @Composable
        override fun Surface(entry: MascotEntry, modifier: Modifier, playing: Boolean) {
            Box(modifier, contentAlignment = Alignment.Center) {
                Box(Modifier.fillMaxSize(BODY).background(BODY_COLOR, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.fillMaxWidth(FACE_WIDTH).fillMaxHeight(FACE_HEIGHT).background(Color.White, CircleShape))
                }
            }
        }

        private const val BODY = 0.6f
        private const val FACE_WIDTH = 0.55f
        private const val FACE_HEIGHT = 0.3f
        private val BODY_COLOR = Color(0xFF00AA88)
    }

    @Test
    fun desktopLight() = snapshot(Shot("canvas-artifact-desktop-light", phone = false, dark = false))

    @Test
    fun desktopDark() = snapshot(Shot("canvas-artifact-desktop-dark", phone = false, dark = true))

    @Test
    fun phoneLight() = snapshot(Shot("canvas-artifact-phone-light", phone = true, dark = false))

    @Test
    fun phoneDark() = snapshot(Shot("canvas-artifact-phone-dark", phone = true, dark = true))

    private companion object {
        const val DESKTOP_WIDTH = 1280
        const val DESKTOP_HEIGHT = 900
        const val PHONE_WIDTH_DP = 412
        const val PHONE_HEIGHT_DP = 915
        const val SCALE = 2
        const val SETTLE_FRAMES = 90
        const val FRAME_MILLIS = 16L
    }
}
