@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.loop.VoiceEngines
import com.letta.mobile.data.voice.loop.VoiceLoopRuntime
import com.letta.mobile.data.voice.session.MicrophoneAccess
import com.letta.mobile.data.voice.session.VoiceChatSessions
import com.letta.mobile.data.voice.session.VoiceEngineFactory
import com.letta.mobile.data.voice.session.VoiceEngineSet
import com.letta.mobile.data.voice.session.VoiceModeController
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.data.voice.session.VoiceSetupStore
import com.letta.mobile.ui.ambient.AmbientMotionStatus
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.surface.ambient.AmbientGlowPlacement
import com.letta.mobile.ui.chat.surface.ambient.ChatAmbient
import com.letta.mobile.ui.chat.surface.ambient.ChatPanelAmbientGlow
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.data.voice.output.SpokenSegment
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.SharedMaterialTheme
import com.letta.mobile.ui.ambient.LocalAmbientVoice
import com.letta.mobile.ui.chat.surface.rememberAmbientVoice
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The voice card docked above the composer, with the composer's voice outline and the page's ambient
 * glow behind (dot texture on), at the review widths (360, 700, 760, 1200 dp) in Dark and Light,
 * written to build/voice-card-render/ for review: the person being heard (their words live), the
 * agent thinking (their last words held) and speaking (its reply lit up to the spoken edge, the rest
 * dim). Neutral demo text only; no audio.
 */
class VoiceModeCardRenderTest {
    private enum class Scene { Listening, Thinking, Speaking }

    @Test
    fun theCardRendersAtEveryReviewWidthInBothThemes() {
        for (dark in listOf(true, false)) {
            for (width in WIDTHS) {
                for (scene in Scene.entries) render(scene, width, dark)
            }
        }
    }

    private fun render(scene: Scene, width: Int, dark: Boolean) = runDesktopComposeUiTest(width = width, height = HEIGHT) {
        // The agent's turn starts at 0 (the card shows from there); its pieces play from then on.
        val feed = FakeVoiceCardFeed(clock = 0)
        feed.spokenSegments.value = REPLY
        val state = MutableStateFlow(
            when (scene) {
                Scene.Listening -> VoiceModeState.Hearing
                Scene.Thinking -> VoiceModeState.WaitingForReply
                Scene.Speaking -> VoiceModeState.Speaking
            },
        )
        val words = MutableStateFlow(VoiceText(HEARD).takeIf { scene != Scene.Speaking })
        val touch = width < LettaDimens.Pane.wideBreakpoint.value
        mainClock.autoAdvance = false
        setContent {
            val scope = rememberCoroutineScope()
            val handle = remember {
                val engines = VoiceEngineFactory { VoiceEngineSet(VoiceEngines(CardQuietEngines, CardQuietEngines, CardQuietEngines)) {} }
                val controller = VoiceModeController(
                    VoiceChatSessions { null },
                    VoiceSetupStore(InMemorySecureSettingsStore(), null),
                    engines,
                    MicrophoneAccess.Granted,
                    // Never driven (the state is the test's): no coroutines on other threads to outlive the scene.
                    VoiceLoopRuntime(scope, Dispatchers.Unconfined),
                )
                VoiceModeHandle(
                    controller,
                    state = state,
                    microphoneLevel = MutableStateFlow(LEVEL),
                    playbackLevel = MutableStateFlow(LEVEL),
                    partialTranscript = words,
                    cardFeed = feed,
                )
            }
            ScenePage(handle, touch, dark)
        }
        feed.clock = SPOKEN_AT
        // Twice: string resources load off the frame clock, and their first frame may show the last scene's words.
        // A few frames each, no more: while the voice glows follow a level every frame redraws the whole
        // page (shader glow included), so a long run of frames made a scene take tens of seconds.
        repeat(2) {
            repeat(FRAMES) { mainClock.advanceTimeByFrame() }
            waitForIdle()
        }
        val name = "${scene.name.lowercase()}-$width-${if (dark) "dark" else "light"}.png"
        val file = File("build/voice-card-render").apply { mkdirs() }.resolve(name)
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
    }

    /** The page: the ambient glow behind, the composer (and so the voice card) at the bottom. */
    @Composable
    private fun ScenePage(handle: VoiceModeHandle, touch: Boolean, dark: Boolean) {
        SharedMaterialTheme(darkTheme = dark) {
            ProvideLettaHost(if (touch) LettaHost.Touch else LettaHost.Pointer) {
                CompositionLocalProvider(LocalVoiceMode provides handle, LocalAmbientVoice provides rememberAmbientVoice(handle)) {
                    Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                            ChatPanelAmbientGlow(
                                ChatAmbient(AmbientMotionStatus.Idle),
                                AmbientGlowPlacement.AboveComposer { COMPOSER_HEIGHT.dp },
                                Modifier.fillMaxSize(),
                            )
                            ChatComposerPanel(
                                ComposerInputs(
                                    composer = ChatComposerUiState(
                                        text = "",
                                        permissionMode = ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted),
                                    ),
                                    actions = RecordingChatActions(),
                                    uiState = ChatUiState(agentName = AGENT, messages = persistentListOf(REPLY_MESSAGE)),
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    private companion object {
        val WIDTHS = listOf(360, 700, 760, 1200)
        const val HEIGHT = 520
        /** Enough for the card's clock to read the feed and what it shows to recompose and draw. */
        const val FRAMES = 3
        const val COMPOSER_HEIGHT = 140
        const val LEVEL = 0.6f
        const val AGENT = "Scout"
        const val HEARD = "Can you help me tidy the release notes before Friday"

        /** Half way through the second piece. */
        const val SPOKEN_AT = 3_000L
        val REPLY = listOf(
            SpokenSegment(0, VoiceText("I can help refactor code,"), startedAtMs = 0, audioDurationMs = 2_000, finishedAtMs = 2_000),
            SpokenSegment(1, VoiceText("draft pull requests,"), startedAtMs = 2_000, audioDurationMs = 2_000, finishedAtMs = null),
        )
        val REPLY_MESSAGE = UiMessage(
            id = "reply",
            role = "assistant",
            content = "I can help refactor code, draft pull requests, or summarize long documents. Which one first?",
            timestamp = "2026-11-18T09:00:00Z",
        )
    }
}
