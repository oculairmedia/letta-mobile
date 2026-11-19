@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
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
import com.letta.mobile.data.voice.session.VoiceSetupStore
import com.letta.mobile.ui.chat.voice.VoiceCardFeed
import com.letta.mobile.ui.chat.voice.VoiceCardSpeed
import com.letta.mobile.ui.chat.voice.VoiceCardSwitch
import com.letta.mobile.ui.chat.voice.VoiceCardConversation
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.data.voice.endpoint.SpeechSpeed
import com.letta.mobile.data.voice.output.SpokenSegment
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Engines that never run: the handle's state, words and feed are pinned by the test. */
internal object CardQuietEngines : VoiceEar, VoiceMouth, VoiceResponder {
    override fun listen(): Flow<VoiceEarEvent> = emptyFlow()

    override fun commitTurn() = Unit

    override val events: Flow<VoiceMouthEvent> = emptyFlow()

    override fun speak(segment: SpeechSegment) = Unit

    override fun flush() = Unit

    override suspend fun stop() = Unit

    override fun respond(utterance: VoiceUtterance): VoiceReply = error("no turn is taken in these tests")
}

/** A feed the test drives: its clock, the reply's pieces, and every control, each recording what was set. */
internal class FakeVoiceCardFeed(var clock: Long = 0L, controls: Boolean = true) : VoiceCardFeed {
    val micMuted = MutableStateFlow(false)
    val speakerMuted = MutableStateFlow(false)
    val speedValue = MutableStateFlow(SpeechSpeed.Normal)
    override val spokenSegments = MutableStateFlow<List<SpokenSegment>>(emptyList())
    override val microphoneMute: VoiceCardSwitch? = VoiceCardSwitch(micMuted) { micMuted.value = it }.takeIf { controls }
    override val speakerMute: VoiceCardSwitch? = VoiceCardSwitch(speakerMuted) { speakerMuted.value = it }.takeIf { controls }
    override val speed: VoiceCardSpeed? = VoiceCardSpeed(speedValue, { speedValue.value = it }).takeIf { controls }

    override fun now(): Long = clock
}

/**
 * The voice card: each running state's speaker and body (the person's words, the agent thinking,
 * the reply, a failed utterance with Retry), the turn timer, the reply lit as it is spoken (a piece at
 * a time under reduced motion), its labelled controls (mutes, speed, fold), and its header on one
 * line on a wide card, on two on a narrow one.
 */
class VoiceModeCardUiTest {
    private val state = MutableStateFlow<VoiceModeState>(VoiceModeState.Listening)
    private val words = MutableStateFlow<VoiceText?>(null)
    private var reply by mutableStateOf<String?>(null)

    /** The reply's lit edge as last drawn (LocalSpokenEdgeProbe). */
    private var litEdge = -1

    @Composable
    private fun rememberHandle(feed: VoiceCardFeed): VoiceModeHandle {
        val scope = rememberCoroutineScope()
        return remember {
            val engines = VoiceEngineFactory {
                VoiceEngineSet(VoiceEngines(CardQuietEngines, CardQuietEngines, CardQuietEngines)) {}
            }
            val controller = VoiceModeController(
                VoiceChatSessions { null },
                VoiceSetupStore(InMemorySecureSettingsStore(), null),
                engines,
                MicrophoneAccess.Granted,
                VoiceLoopRuntime(scope, Dispatchers.Default),
            )
            VoiceModeHandle(controller, state = state, partialTranscript = words, cardFeed = feed)
        }
    }

    private fun ComposeUiTest.showCard(feed: VoiceCardFeed, width: Dp = 760.dp, reducedMotion: Boolean = false) = setContent {
        val handle = rememberHandle(feed)
        MaterialTheme {
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion, LocalSpokenEdgeProbe provides { litEdge = it }) {
                Box(Modifier.width(width)) {
                    VoiceModeCard(handle, VoiceCardConversation("Scout", reply), seamGlow = { Color.Transparent })
                }
            }
        }
    }

    private fun ComposeUiTest.inCard(text: String) =
        onNode(hasText(text) and hasAnyAncestor(hasTestTag(VoiceModeTags.CARD)), useUnmergedTree = true)

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    /** Two frames: one for the card's clock to read the feed, one for what it shows to recompose. */
    private fun ComposeUiTest.frame() {
        repeat(2) { mainClock.advanceTimeByFrame() }
        waitForIdle()
    }

    @Test
    fun eachRunningStateShowsWhoseTurnItIsAndWhatTheyAreSaying() = runComposeUiTest {
        showCard(FakeVoiceCardFeed())
        waitForIdle()
        onNodeWithTag(VoiceModeTags.CARD_SPEAKER, useUnmergedTree = true).assert(hasText("You"))
        // The status is the header's; until there are words the body is one quiet caption line.
        onNodeWithTag(VoiceModeTags.CARD_STATUS, useUnmergedTree = true).assert(hasText("Listening"))
        onNodeWithTag(VoiceModeTags.CARD_CAPTION, useUnmergedTree = true).assert(hasText("Listening…"))
        onNodeWithTag(VoiceModeTags.CARD_BODY, useUnmergedTree = true).assertDoesNotExist()

        state.value = VoiceModeState.Hearing
        words.value = VoiceText("what time is it in Lisbon")
        waitForIdle()
        inCard("what time is it in Lisbon").assertExists()
        onNodeWithTag(VoiceModeTags.CARD_STATUS, useUnmergedTree = true).assert(hasText("Hearing you"))

        state.value = VoiceModeState.WaitingForReply
        waitForIdle()
        onNodeWithTag(VoiceModeTags.CARD_SPEAKER, useUnmergedTree = true).assert(hasText("Scout"))
        onNodeWithTag(VoiceModeTags.CARD_STATUS, useUnmergedTree = true).assert(hasText("Thinking…"))
        // While the reply is awaited, the person's last words stay in view.
        inCard("what time is it in Lisbon").assertExists()

        reply = "It is just after nine in the evening there."
        state.value = VoiceModeState.Speaking
        waitForIdle()
        inCard("It is just after nine in the evening there.").assertExists()
        inCard("Speaking… — press to interrupt").assertDoesNotExist()
        onNodeWithTag(VoiceModeTags.CARD_INTERRUPT).assertContentDescriptionEquals("Stop speaking and listen")

        state.value = VoiceModeState.TurnFailed(VoiceErrorCode.TRANSCRIPTION_FAILED, retryable = true)
        waitForIdle()
        onNodeWithTag(VoiceModeTags.CARD_SPEAKER, useUnmergedTree = true).assert(hasText("You"))
        onNodeWithTag(VoiceModeTags.CARD_ACTION).assert(hasText("Retry"))

        state.value = VoiceModeState.Off
        waitForIdle()
        onNodeWithTag(VoiceModeTags.CARD).assertDoesNotExist()
    }

    @Test
    fun theTimerCountsTheTurnAndStartsAgainWhenTheSpeakerChanges() = runComposeUiTest {
        val feed = FakeVoiceCardFeed(clock = 1_000)
        mainClock.autoAdvance = false
        showCard(feed)
        frame()
        // The timer is read as one phrase ("0:06 on this turn"), not as its bare digits.
        val timer = { onNodeWithTag(VoiceModeTags.CARD_TIMER, useUnmergedTree = true) }
        timer().assertContentDescriptionEquals("0:00 on this turn")
        feed.clock = 7_400
        frame()
        timer().assertContentDescriptionEquals("0:06 on this turn")
        state.value = VoiceModeState.Speaking
        frame()
        timer().assertContentDescriptionEquals("0:00 on this turn")
        feed.clock = 102_400
        frame()
        timer().assertContentDescriptionEquals("1:35 on this turn")
    }

    @Test
    fun theSpokenWordsLightUpAsTheReplyPlays() = litAsItPlays(reducedMotion = false)

    @Test
    fun underReducedMotionTheReplyLightsAPieceAtATime() = litAsItPlays(reducedMotion = true)

    private fun litAsItPlays(reducedMotion: Boolean) = runComposeUiTest {
        val feed = FakeVoiceCardFeed(clock = 0)
        state.value = VoiceModeState.Speaking
        mainClock.autoAdvance = false
        showCard(feed, reducedMotion = reducedMotion)
        val first = "I can help refactor code,"
        val second = "draft pull requests,"
        // The chat's reply holds the words not yet spoken: they show dimmed after the lit ones.
        reply = "$first $second or summarize complex documents."
        feed.spokenSegments.value = listOf(
            SpokenSegment(0, VoiceText(first), startedAtMs = 0, audioDurationMs = 2_000, finishedAtMs = 2_000),
            SpokenSegment(1, VoiceText(second), startedAtMs = 2_000, audioDurationMs = 2_000, finishedAtMs = null),
        )
        feed.clock = 2_000
        frame()
        assertEquals(if (reducedMotion) first.length + 1 + second.length else first.length, litCharacters())
        feed.clock = 3_000
        frame()
        val halfway = first.length + ((1 + second.length) * 0.5f).roundToInt()
        assertEquals(if (reducedMotion) first.length + 1 + second.length else halfway, litCharacters())
        feed.spokenSegments.value = feed.spokenSegments.value.map { if (it.index == 1) it.copy(finishedAtMs = 4_000) else it }
        feed.clock = 4_000
        frame()
        assertEquals(first.length + 1 + second.length, litCharacters(), "a finished piece lights whole")
        assertEquals(reply, bodyText().text, "the rest of the reply shows, dimmed")
    }

    /** How many characters of the card's body are in the bright ink (the body's first span). */
    @Suppress("UnusedReceiverParameter")
    private fun ComposeUiTest.litCharacters(): Int = litEdge

    private fun ComposeUiTest.bodyText() =
        onNodeWithTag(VoiceModeTags.CARD_BODY, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text].single()

    /**
     * The card shows only the reply being spoken: an earlier reply's pieces still in the feed (they
     * started before the agent's turn) are left out, and a chat reply that is not the one being spoken
     * (the newest reply has not reached the chat yet) is never shown in its place.
     */
    @Test
    fun theBodyShowsOnlyTheReplyBeingSpokenNow() = runComposeUiTest {
        val feed = FakeVoiceCardFeed(clock = 1_000)
        state.value = VoiceModeState.Speaking
        mainClock.autoAdvance = false
        showCard(feed)
        val first = "That one is about the first question."
        reply = first
        feed.spokenSegments.value = listOf(
            SpokenSegment(0, VoiceText(first), startedAtMs = 1_200, audioDurationMs = 800, finishedAtMs = 2_000),
        )
        feed.clock = 2_000
        frame()
        assertEquals(first, bodyText().text)

        // The person speaks, then the agent's next turn: its earlier pieces are still in the feed.
        feed.clock = 3_000
        state.value = VoiceModeState.Hearing
        frame()
        feed.clock = 4_000
        state.value = VoiceModeState.WaitingForReply
        frame()
        val second = "And this answers the second one."
        feed.spokenSegments.value = feed.spokenSegments.value +
            SpokenSegment(1, VoiceText(second), startedAtMs = 4_500, audioDurationMs = 1_000, finishedAtMs = null)
        feed.clock = 5_000
        state.value = VoiceModeState.Speaking
        frame()
        assertEquals(second, bodyText().text, "only the second reply, though the chat still holds the first")

        reply = "$second Shall I go on?"
        frame()
        assertEquals("$second Shall I go on?", bodyText().text, "the second reply's rest, dimmed")
    }

    @Test
    fun theMutesAndTheSpeedAreLabelledControlsThatSetTheFeed() = runComposeUiTest {
        val feed = FakeVoiceCardFeed()
        showCard(feed, width = 1200.dp)
        waitForIdle()
        onNodeWithTag(VoiceModeTags.CARD_MIC)
            .assertContentDescriptionEquals("Mute microphone")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performClick()
        waitForIdle()
        assertTrue(feed.micMuted.value, "the microphone mute is set")
        onNodeWithTag(VoiceModeTags.CARD_MIC).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        onNodeWithTag(VoiceModeTags.CARD_SPEAKER_MUTE).assertContentDescriptionEquals("Mute speaker").performClick()
        waitForIdle()
        assertTrue(feed.speakerMuted.value, "the speaker mute is set")

        onNodeWithTag(VoiceModeTags.CARD_SPEED)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1×"))
            .performClick()
        waitForIdle()
        onNodeWithTag("${VoiceModeTags.CARD_SPEED}-1.25").performClick()
        waitForIdle()
        assertEquals(1.25f, feed.speedValue.value.value)
        onNodeWithTag(VoiceModeTags.CARD_SPEED).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1.25×"))
    }

    @Test
    fun foldingLeavesTheHeaderLineAndUnfoldingBringsTheBodyBack() = runComposeUiTest {
        showCard(FakeVoiceCardFeed(), width = 360.dp)
        waitForIdle()
        onNodeWithTag(VoiceModeTags.CARD_CAPTION, useUnmergedTree = true).assertExists()
        onNodeWithTag(VoiceModeTags.CARD_COLLAPSE).assertContentDescriptionEquals("Fold the voice card").performClick()
        waitForIdle()
        onNodeWithTag(VoiceModeTags.CARD_CAPTION, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(VoiceModeTags.CARD_MIC).assertDoesNotExist()
        onNodeWithTag(VoiceModeTags.CARD_END).assertExists()
        onNodeWithTag(VoiceModeTags.CARD_SPEAKER, useUnmergedTree = true).assertExists()
        onNodeWithTag(VoiceModeTags.CARD_COLLAPSE).assertContentDescriptionEquals("Unfold the voice card").performClick()
        waitForIdle()
        onNodeWithTag(VoiceModeTags.CARD_CAPTION, useUnmergedTree = true).assertExists()
    }

    @Test
    fun withoutControlsFromTheControllerTheirButtonsAreLeftOut() = runComposeUiTest {
        showCard(FakeVoiceCardFeed(controls = false))
        waitForIdle()
        onNodeWithTag(VoiceModeTags.CARD_MIC).assertDoesNotExist()
        onNodeWithTag(VoiceModeTags.CARD_SPEAKER_MUTE).assertDoesNotExist()
        onNodeWithTag(VoiceModeTags.CARD_SPEED).assertDoesNotExist()
        onNodeWithTag(VoiceModeTags.CARD_END).assertExists()
        onNodeWithTag(VoiceModeTags.CARD_COLLAPSE).assertExists()
    }

    private fun headerHolds(width: Dp, oneLine: Boolean) = runComposeUiTest {
        showCard(FakeVoiceCardFeed(), width = width)
        waitForIdle()
        val where = "at $width"
        val card = bounds(VoiceModeTags.CARD)
        val speaker = bounds(VoiceModeTags.CARD_SPEAKER)
        val mic = bounds(VoiceModeTags.CARD_MIC)
        val end = bounds(VoiceModeTags.CARD_END)
        listOf(VoiceModeTags.CARD_SPEAKER, VoiceModeTags.CARD_MIC, VoiceModeTags.CARD_SPEED, VoiceModeTags.CARD_END).forEach { tag ->
            val part = bounds(tag)
            assertTrue(part.left >= card.left && part.right <= card.right + 1.dp, "$tag stays inside the card $where")
        }
        val speed = bounds(VoiceModeTags.CARD_SPEED)
        // End and the speed pill draw as tall as the round controls; their semantics may report the
        // platform's minimum touch target around them, never more.
        listOf(end, speed).forEach { pill ->
            val fits = pill.height in mic.height..LettaControlTokens.minTouchTarget
            assertTrue(fits, "a pill ${pill.height} sized with the round controls $where")
        }
        assertTrue(abs((mic.width - mic.height).value) < 1f, "the round controls are round: ${mic.width} x ${mic.height} $where")
        val centre = { rect: DpRect -> (rect.top + rect.bottom) / 2 }
        assertTrue(abs((centre(speed) - centre(mic)).value) < 2f, "the speed pill shares the round controls' line $where")
        assertTrue(abs((centre(end) - centre(speaker)).value) < 2f, "End shares the speaker's line $where")
        val sameLine = abs((centre(mic) - centre(speaker)).value) < 2f
        assertEquals(oneLine, sameLine, "the controls share the speaker's line: $oneLine $where")
        if (!oneLine) assertTrue(mic.top >= speaker.bottom, "on two lines the controls sit under the speaker $where")
    }

    @Test
    fun theHeaderTakesTwoLinesAt360() = headerHolds(360.dp, oneLine = false)

    @Test
    fun theHeaderIsOneLineAt700() = headerHolds(700.dp, oneLine = true)

    @Test
    fun theHeaderIsOneLineAt760() = headerHolds(760.dp, oneLine = true)

    @Test
    fun theHeaderIsOneLineAt1200() = headerHolds(1200.dp, oneLine = true)
}
