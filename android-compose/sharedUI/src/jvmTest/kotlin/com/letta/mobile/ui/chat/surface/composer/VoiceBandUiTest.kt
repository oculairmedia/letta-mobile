@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import com.letta.mobile.data.voice.engine.VoiceReply
import com.letta.mobile.data.voice.engine.VoiceResponder
import com.letta.mobile.data.voice.loop.VoiceEngines
import com.letta.mobile.data.voice.loop.VoiceLoopRuntime
import com.letta.mobile.data.voice.session.MicrophoneAccess
import com.letta.mobile.data.voice.session.VoiceChatSessions
import com.letta.mobile.data.voice.session.VoiceEngineFactory
import com.letta.mobile.data.voice.session.VoiceEngineSet
import com.letta.mobile.data.voice.session.VoiceModeController
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.data.voice.session.VoiceSetupGap
import com.letta.mobile.data.voice.session.VoiceSetupStore
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.surface.ChatVoiceBandBackdrop
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.surface.ambient.AmbientGlowAnimatedKey
import com.letta.mobile.ui.chat.surface.ambient.CHAT_AMBIENT_GLOW_TAG
import com.letta.mobile.ui.chat.surface.ambient.VOICE_WAVE_TAG
import com.letta.mobile.ui.chat.surface.ambient.VoiceWaveAnimatedKey
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An ear for a controller that is never started: these tests pin the band's state instead. */
private class UnusedBandEar : VoiceEar {
    override fun listen(): Flow<VoiceEarEvent> = emptyFlow()

    override fun commitTurn() = Unit
}

private class UnusedBandMouth : VoiceMouth {
    override val events: Flow<VoiceMouthEvent> = emptyFlow()

    override fun speak(segment: SpeechSegment) = Unit

    override fun flush() = Unit

    override suspend fun stop() = Unit
}

private object UnusedBandResponder : VoiceResponder {
    override fun respond(utterance: VoiceUtterance): VoiceReply = error("no turn is taken in these tests")
}

/**
 * Voice mode's band: inside the chat column, directly above the prompt bar, the same fixed height
 * in every state at the phone, tablet and desktop widths in both input idioms; it never covers the
 * conversation or the window, keeps its controls reachable, and each state picks its visual (the
 * reactive wave, the thinking glow, or the still surface). Pixels are not asserted: the band says
 * which visual it shows through its semantics.
 */
class VoiceBandUiTest {
    private val drafted = ChatComposerUiState(text = "ready", canSend = true)

    /** Every state the band shows, with the visual it should pick. */
    private val states: List<Pair<VoiceModeState, VoiceBandVisual>> = listOf(
        VoiceModeState.Listening to VoiceBandVisual.MicrophoneWave,
        VoiceModeState.Hearing to VoiceBandVisual.MicrophoneWave,
        VoiceModeState.WaitingForReply to VoiceBandVisual.Thinking,
        VoiceModeState.Speaking to VoiceBandVisual.PlaybackWave,
        VoiceModeState.TurnFailed(VoiceErrorCode.TRANSCRIPTION_FAILED, retryable = true) to VoiceBandVisual.Still,
        VoiceModeState.Error(VoiceErrorCode.ENGINE_FAILED) to VoiceBandVisual.Still,
        VoiceModeState.NeedsSetup(VoiceSetupGap.NO_ENDPOINT) to VoiceBandVisual.Still,
        VoiceModeState.NeedsMicPermission to VoiceBandVisual.Still,
    )

    @Composable
    private fun rememberPinnedHandle(state: MutableStateFlow<VoiceModeState>): VoiceModeHandle {
        val scope = rememberCoroutineScope()
        return remember {
            val store = VoiceSetupStore(InMemorySecureSettingsStore(), InMemorySecureSettingsStore())
            val engines = VoiceEngineFactory { VoiceEngineSet(VoiceEngines(UnusedBandEar(), UnusedBandResponder, UnusedBandMouth())) {} }
            val controller = VoiceModeController(
                VoiceChatSessions { null },
                store,
                engines,
                MicrophoneAccess.Granted,
                VoiceLoopRuntime(scope, Dispatchers.Default),
            )
            VoiceModeHandle(controller, state = state, microphoneLevel = MutableStateFlow(0.4f), playbackLevel = MutableStateFlow(0.7f))
        }
    }

    /** A page [width] wide: a stand-in conversation filling the space above the composer, as the chat page lays them out. */
    private fun ComposeUiTest.showPage(
        width: Dp,
        style: LettaInput,
        state: MutableStateFlow<VoiceModeState>,
        reducedMotion: Boolean = false,
    ) = setContent {
        val handle = rememberPinnedHandle(state)
        MaterialTheme {
            CompositionLocalProvider(
                LocalLettaHost provides LettaHost(input = style),
                LocalVoiceMode provides handle,
                LocalVoiceBandBackdrop provides ChatVoiceBandBackdrop,
                LocalReducedMotion provides reducedMotion,
            ) {
                Column(Modifier.width(width).height(PageHeight)) {
                    Box(Modifier.fillMaxWidth().weight(1f).testTag(TIMELINE))
                    ChatComposerPanel(ComposerInputs(composer = drafted, actions = RecordingChatActions()))
                }
            }
        }
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun <T> ComposeUiTest.semantic(tag: String, key: SemanticsPropertyKey<T>): T =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[key]

    private fun DpRect.toRect(): Rect = Rect(left.value, top.value, right.value, bottom.value)

    private fun DpRect.contains(other: DpRect): Boolean =
        other.left >= left - Slack && other.right <= right + Slack && other.top >= top - Slack && other.bottom <= bottom + Slack

    private fun assertNear(expected: Dp, actual: Dp, message: String) =
        assertTrue(abs((expected - actual).value) < 0.5f, "$message: expected $expected, was $actual")

    /** Walks every state at [width] in [style] and checks where the band is, its height, its controls and its visual. */
    private fun bandHolds(width: Dp, style: LettaInput) = runComposeUiTest {
        val state = MutableStateFlow<VoiceModeState>(VoiceModeState.Listening)
        showPage(width, style, state)
        waitForIdle()
        val barTag = if (style == LettaInput.Touch) ComposerTestTags.TOUCH_BAR else ComposerTestTags.CARD
        val height = if (style == LettaInput.Touch) ChatComposerDimens.voiceBandTouch else ChatComposerDimens.voiceBandPointer
        val barAtFirst = bounds(barTag)
        val timelineAtFirst = bounds(TIMELINE)
        for ((shown, visual) in states) {
            state.value = shown
            waitForIdle()
            val where = "at $width ($style) while $shown"
            val band = bounds(VoiceModeTags.STRIP)
            val bar = bounds(barTag)
            val timeline = bounds(TIMELINE)

            assertNear(height, band.height, "the band keeps its fixed height $where")
            assertEquals(barAtFirst, bar, "the bar never moves $where")
            assertEquals(timelineAtFirst, timeline, "the conversation never moves $where")
            assertTrue(band.bottom <= bar.top + Slack, "the band sits above the bar $where")
            assertTrue(bar.top - band.bottom <= LettaDimens.Space.lg, "the band sits directly on the bar $where")
            assertTrue(band.top >= timeline.bottom - Slack, "the band never covers the conversation $where")
            assertFalse(band.toRect().overlaps(timeline.toRect()), "the band never overlaps the conversation $where")
            assertTrue(band.left >= 0.dp && band.right <= width, "the band stays inside the chat column $where")
            assertTrue(band.width <= ChatColumnMaxWidth + Slack, "the band is no wider than the chat column $where")
            assertTrue(band.height < PageHeight / 4, "the band never fills the window $where")

            onNodeWithTag(VoiceModeTags.STRIP_CLOSE, useUnmergedTree = true).assertHasClickAction()
            assertTrue(band.contains(bounds(VoiceModeTags.STRIP_CLOSE)), "the close control is inside the band $where")
            if (shown == VoiceModeState.Speaking || shown is VoiceModeState.TurnFailed || shown is VoiceModeState.Error) {
                onNodeWithTag(VoiceModeTags.STRIP_ACTION, useUnmergedTree = true).assertHasClickAction()
                assertTrue(band.contains(bounds(VoiceModeTags.STRIP_ACTION)), "the action is inside the band $where")
            }

            assertEquals(visual, semantic(VoiceModeTags.STRIP, VoiceBandVisualKey), "the band's visual $where")
            assertEquals(visual != VoiceBandVisual.Still, semantic(VoiceModeTags.STRIP, VoiceBandAnimatedKey), "whether it moves $where")
            val wave = onNodeWithTag(VOICE_WAVE_TAG, useUnmergedTree = true)
            val glow = onNodeWithTag(CHAT_AMBIENT_GLOW_TAG, useUnmergedTree = true)
            when (visual.layer) {
                VoiceBandVisual.MicrophoneWave -> {
                    wave.assertExists()
                    glow.assertDoesNotExist()
                    assertTrue(band.contains(bounds(VOICE_WAVE_TAG)), "the wave is drawn inside the band $where")
                }
                VoiceBandVisual.Thinking -> {
                    glow.assertExists()
                    wave.assertDoesNotExist()
                    assertTrue(band.contains(bounds(CHAT_AMBIENT_GLOW_TAG)), "the glow is drawn inside the band $where")
                }
                else -> {
                    wave.assertDoesNotExist()
                    glow.assertDoesNotExist()
                }
            }
        }
        state.value = VoiceModeState.Off
        waitForIdle()
        onNodeWithTag(VoiceModeTags.STRIP).assertDoesNotExist()
        assertEquals(barAtFirst, bounds(barTag), "the bar stays put once voice mode is off")
    }

    @Test
    fun theTouchBandHoldsAt360() = bandHolds(360.dp, LettaInput.Touch)

    @Test
    fun theTouchBandHoldsAt700() = bandHolds(700.dp, LettaInput.Touch)

    @Test
    fun theTouchBandHoldsAt760() = bandHolds(760.dp, LettaInput.Touch)

    @Test
    fun theTouchBandHoldsAt1200() = bandHolds(1200.dp, LettaInput.Touch)

    @Test
    fun thePointerBandHoldsAt360() = bandHolds(360.dp, LettaInput.Pointer)

    @Test
    fun thePointerBandHoldsAt700() = bandHolds(700.dp, LettaInput.Pointer)

    @Test
    fun thePointerBandHoldsAt760() = bandHolds(760.dp, LettaInput.Pointer)

    @Test
    fun thePointerBandHoldsAt1200() = bandHolds(1200.dp, LettaInput.Pointer)

    @Test
    fun underReducedMotionTheBandHoldsStillAtTheSameHeight() = runComposeUiTest {
        val state = MutableStateFlow<VoiceModeState>(VoiceModeState.Hearing)
        showPage(1200.dp, LettaInput.Pointer, state, reducedMotion = true)
        waitForIdle()
        assertNear(ChatComposerDimens.voiceBandPointer, bounds(VoiceModeTags.STRIP).height, "the band keeps its height")
        assertEquals(VoiceBandVisual.MicrophoneWave, semantic(VoiceModeTags.STRIP, VoiceBandVisualKey))
        assertFalse(semantic(VoiceModeTags.STRIP, VoiceBandAnimatedKey), "the band does not move")
        assertFalse(semantic(VOICE_WAVE_TAG, VoiceWaveAnimatedKey), "the wave is one still frame")

        state.value = VoiceModeState.WaitingForReply
        waitForIdle()
        onNodeWithTag(VOICE_WAVE_TAG, useUnmergedTree = true).assertDoesNotExist()
        assertEquals(VoiceBandVisual.Thinking, semantic(VoiceModeTags.STRIP, VoiceBandVisualKey))
        assertFalse(semantic(CHAT_AMBIENT_GLOW_TAG, AmbientGlowAnimatedKey), "the thinking glow holds still")
        assertNear(ChatComposerDimens.voiceBandPointer, bounds(VoiceModeTags.STRIP).height, "and the band keeps its height")
    }

    @Test
    fun eachStatePicksItsVisual() {
        assertEquals(VoiceBandVisual.MicrophoneWave, voiceBandVisual(VoiceModeState.Listening))
        assertEquals(VoiceBandVisual.MicrophoneWave, voiceBandVisual(VoiceModeState.Hearing))
        assertEquals(VoiceBandVisual.Thinking, voiceBandVisual(VoiceModeState.WaitingForReply))
        assertEquals(VoiceBandVisual.PlaybackWave, voiceBandVisual(VoiceModeState.Speaking))
        assertEquals(VoiceBandVisual.Still, voiceBandVisual(VoiceModeState.NeedsConversation))
        assertEquals(VoiceBandVisual.MicrophoneWave, VoiceBandVisual.PlaybackWave.layer, "both waves draw on one layer")
    }

    private companion object {
        val PageHeight = 900.dp
        val Slack = 0.5.dp
        const val TIMELINE = "voice-band-test-timeline"
    }
}
