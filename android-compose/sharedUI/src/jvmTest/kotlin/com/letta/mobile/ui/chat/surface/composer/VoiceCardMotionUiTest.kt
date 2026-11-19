@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
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
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.components.motion.LettaPanelMotion
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The voice card's entrance and exit on its dock, under a paused clock: it extends up out of the prompt
 * bar (the dock grows over the unfold, the bar never moves, the card's bottom stays tucked under the
 * bar) and settles, and on End it folds back into the bar quickly. Under reduced motion nothing moves
 * in space: the card is at its full size from its first frame to its last, and only fades.
 */
class VoiceCardMotionUiTest {
    private val state = MutableStateFlow<VoiceModeState>(VoiceModeState.Off)

    private fun ComposeUiTest.showDock(reduced: Boolean) {
        mainClock.autoAdvance = false
        setContent {
            val scope = rememberCoroutineScope()
            val handle = remember {
                val engines = VoiceEngineFactory { VoiceEngineSet(VoiceEngines(CardQuietEngines, CardQuietEngines, CardQuietEngines)) {} }
                // The controller is never driven here (the state is the test's): its coroutines run on the composition's clock.
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
            MaterialTheme {
                CompositionLocalProvider(LocalVoiceMode provides handle, LocalReducedMotion provides reduced) {
                    Box(Modifier.width(WIDTH).height(PAGE_HEIGHT), contentAlignment = Alignment.BottomCenter) {
                        VoiceCardDock(ChatUiState(agentName = "Scout"), Modifier.testTag(DOCK)) {
                            Box(Modifier.fillMaxWidth().height(BAR_HEIGHT).testTag(BAR))
                        }
                    }
                }
            }
        }
        settle()
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun ComposeUiTest.dockHeight(): Dp = bounds(DOCK).height

    private fun ComposeUiTest.advance(millis: Long) {
        mainClock.advanceTimeBy(millis)
        waitForIdle()
    }

    /** Long past every entrance, exit and afterglow, and a few frames for the state to reach the card. */
    private fun ComposeUiTest.settle() = advance(SETTLED_MILLIS)

    @Test
    fun theCardExtendsUpFromTheBarAndSettles() = runComposeUiTest {
        showDock(reduced = false)
        val barTop = bounds(BAR).top
        assertEquals(BAR_HEIGHT, dockHeight(), "only the bar while voice mode is off")

        state.value = VoiceModeState.Listening
        advance(EARLY_MILLIS)
        val early = dockHeight()
        assertEquals(barTop, bounds(BAR).top, "the bar never moves while the card extends")
        onNodeWithTag(VoiceModeTags.CARD).assertExists()
        advance(LettaPanelMotion.ENTER_MILLIS / 2L)
        val midway = dockHeight()
        settle()
        val full = dockHeight()
        assertTrue(early > BAR_HEIGHT && early < midway && midway < full, "the dock grows over the unfold: $early, $midway, $full")
        assertEquals(barTop, bounds(BAR).top, "the bar is where it was once the card has landed")
        val card = bounds(VoiceModeTags.CARD)
        assertTrue(card.bottom > barTop, "the card's bottom stays tucked under the bar")
        advance(FRAME_MILLIS * 4)
        assertEquals(full, dockHeight(), "the card has settled under the paused clock")
    }

    @Test
    fun aChangeOfTurnShowsAtOnceOnTheDockedCard() = runComposeUiTest {
        showDock(reduced = false)
        state.value = VoiceModeState.Listening
        settle()
        onNodeWithTag(VoiceModeTags.CARD_STATUS, useUnmergedTree = true).assert(hasText("Listening"))

        // Two frames: one for the state to reach the card, one for what it shows to draw.
        state.value = VoiceModeState.WaitingForReply
        advance(FRAME_MILLIS * 2)
        onNodeWithTag(VoiceModeTags.CARD_SPEAKER, useUnmergedTree = true).assert(hasText("Scout"))
        onNodeWithTag(VoiceModeTags.CARD_STATUS, useUnmergedTree = true).assert(hasText("Thinking…"))
    }

    @Test
    fun endFoldsTheCardBackIntoTheBarQuickly() = runComposeUiTest {
        showDock(reduced = false)
        state.value = VoiceModeState.Listening
        settle()
        val full = dockHeight()

        state.value = VoiceModeState.Off
        advance(LettaPanelMotion.EXIT_MILLIS / 2L)
        onNodeWithTag(VoiceModeTags.CARD).assertExists()
        val folding = dockHeight()
        assertTrue(folding < full && folding > BAR_HEIGHT, "the card folds back into the bar: $folding of $full")
        advance(LettaPanelMotion.EXIT_MILLIS.toLong() + FRAME_MILLIS * 2)
        onNodeWithTag(VoiceModeTags.CARD).assertDoesNotExist()
        assertEquals(BAR_HEIGHT, dockHeight(), "only the bar once the card has gone")
    }

    @Test
    fun underReducedMotionTheCardOnlyFades() = runComposeUiTest {
        showDock(reduced = true)
        state.value = VoiceModeState.Listening
        advance(EARLY_MILLIS)
        onNodeWithTag(VoiceModeTags.CARD).assertExists()
        val first = dockHeight()
        val firstCard = bounds(VoiceModeTags.CARD)
        settle()
        assertEquals(first, dockHeight(), "no growth: the card is at its full size from its first frame")
        assertEquals(firstCard, bounds(VoiceModeTags.CARD), "no movement or scaling of the card")

        state.value = VoiceModeState.Off
        advance(LettaPanelMotion.REDUCED_FADE_MILLIS / 2L)
        assertEquals(first, dockHeight(), "no folding: the card keeps its size while it fades")
        advance(LettaPanelMotion.REDUCED_FADE_MILLIS.toLong() + FRAME_MILLIS * 2)
        onNodeWithTag(VoiceModeTags.CARD).assertDoesNotExist()
        assertEquals(BAR_HEIGHT, dockHeight())
    }

    private companion object {
        const val DOCK = "voice-card-dock"
        const val BAR = "voice-card-bar"
        val WIDTH = 360.dp
        val PAGE_HEIGHT = 520.dp
        val BAR_HEIGHT = 56.dp
        const val FRAME_MILLIS = 16L
        const val EARLY_MILLIS = FRAME_MILLIS * 3
        const val SETTLED_MILLIS = 1_500L
    }
}
