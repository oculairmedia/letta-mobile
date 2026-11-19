@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
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
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.voice.loop.VoiceEngines
import com.letta.mobile.data.voice.loop.VoiceLoopRuntime
import com.letta.mobile.data.voice.session.MicrophoneAccess
import com.letta.mobile.data.voice.session.VoiceChatSessions
import com.letta.mobile.data.voice.session.VoiceEngineFactory
import com.letta.mobile.data.voice.session.VoiceEngineSet
import com.letta.mobile.data.voice.session.VoiceModeController
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.data.voice.session.VoiceSetupStore
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.SharedMaterialTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Frames of the voice card extending up out of the prompt bar as voice mode starts, at the review
 * widths in Dark and Light, written to build/voice-card-motion/ for review: the bar alone, three
 * frames of the unfold, and the landed card. A paused clock steps the frames; neutral demo text only.
 */
class VoiceCardMotionRenderTest {
    @Test
    fun theEntranceRendersAtEveryReviewWidthInBothThemes() {
        for (dark in listOf(true, false)) {
            for (width in WIDTHS) render(width, dark)
        }
    }

    private fun render(width: Int, dark: Boolean) = runDesktopComposeUiTest(width = width, height = HEIGHT) {
        val state = MutableStateFlow<VoiceModeState>(VoiceModeState.Off)
        val touch = width < LettaDimens.Pane.wideBreakpoint.value
        mainClock.autoAdvance = false
        setContent {
            val scope = rememberCoroutineScope()
            val handle = remember {
                val engines = VoiceEngineFactory { VoiceEngineSet(VoiceEngines(CardQuietEngines, CardQuietEngines, CardQuietEngines)) {} }
                // Never driven (the state is the test's): its coroutines stay on the test's thread.
                val runtime = VoiceLoopRuntime(scope, Dispatchers.Unconfined)
                val controller = VoiceModeController(
                    VoiceChatSessions { null },
                    VoiceSetupStore(InMemorySecureSettingsStore(), null),
                    engines,
                    MicrophoneAccess.Granted,
                    runtime,
                )
                VoiceModeHandle(controller, state = state, cardFeed = FakeVoiceCardFeed())
            }
            SharedMaterialTheme(darkTheme = dark) {
                ProvideLettaHost(if (touch) LettaHost.Touch else LettaHost.Pointer) {
                    CompositionLocalProvider(LocalVoiceMode provides handle) {
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                                ChatComposerPanel(
                                    ComposerInputs(
                                        composer = ChatComposerUiState(
                                            text = "",
                                            permissionMode = ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted),
                                        ),
                                        actions = RecordingChatActions(),
                                        uiState = ChatUiState(agentName = AGENT),
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                }
            }
        }
        val theme = if (dark) "dark" else "light"
        val dir = File("build/voice-card-motion").apply { mkdirs() }
        // String resources and the bar's own entrance settle first.
        mainClock.advanceTimeBy(SETTLE_MILLIS)
        var at = 0L
        for (frame in FRAMES_AT) {
            mainClock.advanceTimeBy(frame - at)
            at = frame
            waitForIdle()
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", dir.resolve("enter-$width-$theme-${frame}ms.png"))
            if (frame == 0L) {
                state.value = VoiceModeState.Listening
                at = 0L
            }
        }
    }

    private companion object {
        val WIDTHS = listOf(360, 700, 760, 1200)
        const val HEIGHT = 420
        const val AGENT = "Scout"
        const val SETTLE_MILLIS = 500L

        /** The bar alone (string resources settled), then times after voice mode starts. */
        val FRAMES_AT = listOf(0L, 48L, 112L, 208L, 1_000L)
    }
}
