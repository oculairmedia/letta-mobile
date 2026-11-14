@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
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
import com.letta.mobile.ui.ambient.AmbientMotionStatus
import com.letta.mobile.ui.ambient.AmbientVoice
import com.letta.mobile.ui.ambient.AmbientVoiceRole
import com.letta.mobile.ui.ambient.LocalAmbientVoice
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.surface.ambient.AmbientGlowPlacement
import com.letta.mobile.ui.chat.surface.ambient.CHAT_AMBIENT_GLOW_TAG
import com.letta.mobile.ui.chat.surface.ambient.ChatAmbient
import com.letta.mobile.ui.chat.surface.ambient.ChatPanelAmbientGlow
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** An ear for a controller that is never started: these tests pin voice mode's state instead. */
private class UnusedOutlineEar : VoiceEar {
    override fun listen(): Flow<VoiceEarEvent> = emptyFlow()

    override fun commitTurn() = Unit
}

private class UnusedOutlineMouth : VoiceMouth {
    override val events: Flow<VoiceMouthEvent> = emptyFlow()

    override fun speak(segment: SpeechSegment) = Unit

    override fun flush() = Unit

    override suspend fun stop() = Unit
}

private object UnusedOutlineResponder : VoiceResponder {
    override fun respond(utterance: VoiceUtterance): VoiceReply = error("no turn is taken in these tests")
}

/**
 * Voice mode around the prompt, with no surface of its own: turning it on and walking its states
 * moves nothing (the card or bar, the conversation, Send, the voice button, the field, the line under
 * the card), the field's placeholder says what it is doing until the person types, the prompt's
 * outline shows whose turn it is (the person's colour while hearing, the agent's while thinking and
 * speaking) without taking a click from the field, the buttons or the last row, failures use the
 * composer's notice with Retry or Set up, and reduced motion holds the outline still. At the phone,
 * tablet and desktop widths in both input idioms.
 */
class VoiceOutlineUiTest {
    /** The voice states that keep the composer as it is, with the placeholder and outline colour each shows. */
    private val running: List<Triple<VoiceModeState, String, VoiceGlowRole>> = listOf(
        Triple(VoiceModeState.Listening, "Listening…", VoiceGlowRole.You),
        Triple(VoiceModeState.Hearing, "Hearing you…", VoiceGlowRole.You),
        Triple(VoiceModeState.WaitingForReply, "Thinking…", VoiceGlowRole.Agent),
        Triple(VoiceModeState.Speaking, "Speaking… — press to interrupt", VoiceGlowRole.Agent),
    )

    @Composable
    private fun rememberPinnedHandle(state: MutableStateFlow<VoiceModeState>): VoiceModeHandle {
        val scope = rememberCoroutineScope()
        return remember {
            val store = VoiceSetupStore(InMemorySecureSettingsStore(), InMemorySecureSettingsStore())
            val engines = VoiceEngineFactory {
                VoiceEngineSet(VoiceEngines(UnusedOutlineEar(), UnusedOutlineResponder, UnusedOutlineMouth())) {}
            }
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

    /** What one page shows and lets the test change. */
    private class Page {
        val state = MutableStateFlow<VoiceModeState>(VoiceModeState.Off)
        var text by mutableStateOf("")
        var lastRowClicks by mutableIntStateOf(0)
        var handle: VoiceModeHandle? = null
    }

    /** A page [width] wide: a conversation (its last row clickable) above the composer, as the chat page lays them out. */
    private fun ComposeUiTest.showPage(width: Dp, style: LettaInput, page: Page, reducedMotion: Boolean = false) = setContent {
        val handle = rememberPinnedHandle(page.state).also { page.handle = it }
        MaterialTheme {
            CompositionLocalProvider(
                LocalLettaHost provides LettaHost(input = style),
                LocalVoiceMode provides handle,
                LocalReducedMotion provides reducedMotion,
            ) {
                Column(Modifier.width(width).height(PageHeight)) {
                    Box(Modifier.fillMaxWidth().weight(1f).testTag(TIMELINE), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().height(LastRowHeight).clickable { page.lastRowClicks++ }.testTag(LAST_ROW))
                    }
                    ChatComposerPanel(
                        ComposerInputs(
                            composer = ChatComposerUiState(
                                text = page.text,
                                canSend = page.text.isNotEmpty(),
                                permissionMode = ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted),
                            ),
                            actions = RecordingChatActions(),
                        ),
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun <T> ComposeUiTest.semantic(tag: String, key: SemanticsPropertyKey<T>): T =
        onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().config[key]

    private fun barTag(style: LettaInput) = if (style == LettaInput.Touch) ComposerTestTags.TOUCH_BAR else ComposerTestTags.CARD

    /** The composer's fixed parts for [style]: the bar or card, Send, the voice button, the field, and the card's under line. */
    private fun fixedTags(style: LettaInput): List<String> = buildList {
        add(barTag(style))
        add(ComposerTestTags.SEND)
        add(VoiceModeTags.BUTTON)
        add(ComposerTestTags.INPUT)
        if (style == LettaInput.Pointer) add(ComposerTestTags.PERMISSION_MODE_LINE)
    }

    /** Where the composer's fixed parts and the conversation sit with voice mode off, with and without text. */
    private class Baseline(val where: String, val bar: String, val fixed: List<String>) {
        lateinit var off: Map<String, DpRect>
        lateinit var typedOff: Map<String, DpRect>
        lateinit var timeline: DpRect
    }

    /** Walks voice mode on through every state at [width] in [style]: bounds, placeholder, outline, clicks, notices. */
    private fun arrangementHolds(width: Dp, style: LettaInput) = runComposeUiTest {
        val page = Page()
        showPage(width, style, page)
        waitForIdle()
        val base = measureBaseline(page, Baseline("at $width ($style)", barTag(style), fixedTags(style)))
        assertEquals(VoiceGlowRole.Idle, semantic(base.bar, VoiceOutlineRoleKey), "no outline while voice mode is off ${base.where}")
        runningStatesHold(page, base)
        clicksGetThrough(page, base)
        failuresUseTheNotice(page, base)
        page.state.value = VoiceModeState.Off
        waitForIdle()
        onNodeWithTag(VoiceModeTags.STRIP).assertDoesNotExist()
        assertFixed(base, base.off, "once voice mode is off")
    }

    private fun ComposeUiTest.measureBaseline(page: Page, base: Baseline): Baseline {
        base.off = base.fixed.associateWith { bounds(it) }
        base.timeline = bounds(TIMELINE)
        // Typed text changes Send's own look (enabled), so with text the baseline is voice mode off with text.
        page.text = "typed"
        waitForIdle()
        base.typedOff = base.fixed.associateWith { bounds(it) }
        page.text = ""
        waitForIdle()
        return base
    }

    private fun ComposeUiTest.assertFixed(base: Baseline, expected: Map<String, DpRect>, context: String) =
        base.fixed.forEach { tag -> assertEquals(expected.getValue(tag), bounds(tag), "$tag keeps its bounds $context ${base.where}") }

    /** Every running state keeps every bound, shows its placeholder until text is typed and its colour role either way. */
    private fun ComposeUiTest.runningStatesHold(page: Page, base: Baseline) {
        val roles = mutableMapOf<VoiceModeState, VoiceGlowRole>()
        for ((shown, placeholder, role) in running) {
            page.text = ""
            page.state.value = shown
            waitForIdle()
            assertFixed(base, base.off, "while $shown")
            assertEquals(base.timeline, bounds(TIMELINE), "the conversation keeps its bounds while $shown ${base.where}")
            onNodeWithTag(VoiceModeTags.STRIP).assertDoesNotExist()
            onNodeWithText(placeholder, useUnmergedTree = true).assertExists()
            assertEquals(role, semantic(base.bar, VoiceOutlineRoleKey), "the outline's colour role while $shown ${base.where}")
            roles[shown] = role

            page.text = "typed"
            waitForIdle()
            onNodeWithText(placeholder, useUnmergedTree = true).assertDoesNotExist()
            assertEquals(role, semantic(base.bar, VoiceOutlineRoleKey), "the outline carries $shown once text is typed ${base.where}")
            assertFixed(base, base.typedOff, "with text while $shown")
        }
        val hearing = roles[VoiceModeState.Hearing]
        assertNotEquals(hearing, roles[VoiceModeState.WaitingForReply], "hearing and thinking differ in colour ${base.where}")
        assertNotEquals(hearing, roles[VoiceModeState.Speaking], "hearing and speaking differ in colour ${base.where}")
    }

    /** The outline only draws: the last row, the field and the voice button still take their clicks. */
    private fun ComposeUiTest.clicksGetThrough(page: Page, base: Baseline) {
        page.state.value = VoiceModeState.Hearing
        page.text = ""
        waitForIdle()
        onNodeWithTag(LAST_ROW).performClick()
        waitForIdle()
        assertEquals(1, page.lastRowClicks, "the last row takes its click ${base.where}")
        onNodeWithTag(ComposerTestTags.INPUT).performClick()
        waitForIdle()
        onNodeWithTag(ComposerTestTags.INPUT).assertIsFocused()
        onNodeWithTag(VoiceModeTags.BUTTON).performClick()
        waitForIdle()
        assertTrue(page.handle!!.setupOpen, "the voice button takes its click ${base.where}")
        page.handle!!.setupOpen = false
    }

    /** Failures use the composer's notice slot; the prompt and its controls stay where they were. */
    private fun ComposeUiTest.failuresUseTheNotice(page: Page, base: Baseline) {
        failures.forEach { (shown, action, role) ->
            page.state.value = shown
            waitForIdle()
            val where = "while $shown ${base.where}"
            onNodeWithTag(VoiceModeTags.STRIP, useUnmergedTree = true).assertExists()
            onNodeWithTag(VoiceModeTags.STRIP_ACTION, useUnmergedTree = true).assertExists()
            onNodeWithText(action, useUnmergedTree = true).assertExists()
            assertEquals(role, semantic(base.bar, VoiceOutlineRoleKey), "the outline $where")
            assertFixed(base, base.off, "while $shown")
            assertEquals(base.timeline.top, bounds(TIMELINE).top, "the conversation keeps its top $where")
            assertTrue(bounds(VoiceModeTags.STRIP).bottom <= base.off.getValue(base.bar).top, "the notice sits above the prompt $where")
        }
        onNodeWithTag(VoiceModeTags.STRIP_CLOSE, useUnmergedTree = true).performClick()
        waitForIdle()
    }

    /** The failure states, with the notice action and outline role each shows. */
    private val failures: List<Triple<VoiceModeState, String, VoiceGlowRole>> = listOf(
        Triple(VoiceModeState.TurnFailed(VoiceErrorCode.TRANSCRIPTION_FAILED, retryable = true), "Retry", VoiceGlowRole.Error),
        Triple(VoiceModeState.Error(VoiceErrorCode.ENGINE_FAILED), "Retry", VoiceGlowRole.Error),
        Triple(VoiceModeState.NeedsSetup(VoiceSetupGap.NO_ENDPOINT), "Set up", VoiceGlowRole.Idle),
    )

    @Test
    fun theTouchArrangementHoldsAt360() = arrangementHolds(360.dp, LettaInput.Touch)

    @Test
    fun theTouchArrangementHoldsAt700() = arrangementHolds(700.dp, LettaInput.Touch)

    @Test
    fun theTouchArrangementHoldsAt760() = arrangementHolds(760.dp, LettaInput.Touch)

    @Test
    fun theTouchArrangementHoldsAt1200() = arrangementHolds(1200.dp, LettaInput.Touch)

    @Test
    fun thePointerArrangementHoldsAt360() = arrangementHolds(360.dp, LettaInput.Pointer)

    @Test
    fun thePointerArrangementHoldsAt700() = arrangementHolds(700.dp, LettaInput.Pointer)

    @Test
    fun thePointerArrangementHoldsAt760() = arrangementHolds(760.dp, LettaInput.Pointer)

    @Test
    fun thePointerArrangementHoldsAt1200() = arrangementHolds(1200.dp, LettaInput.Pointer)

    @Test
    fun theOutlineMovesWithAVoiceAndHoldsStillUnderReducedMotion() {
        listOf(false to true, true to false).forEach { (reduced, moves) ->
            runComposeUiTest {
                val page = Page()
                showPage(1200.dp, LettaInput.Pointer, page, reducedMotion = reduced)
                page.state.value = VoiceModeState.Hearing
                waitForIdle()
                assertEquals(VoiceGlowRole.You, semantic(ComposerTestTags.CARD, VoiceOutlineRoleKey))
                assertEquals(moves, semantic(ComposerTestTags.CARD, VoiceOutlineAnimatedKey), "reduced motion $reduced")
                page.state.value = VoiceModeState.WaitingForReply
                waitForIdle()
                assertFalse(semantic(ComposerTestTags.CARD, VoiceOutlineAnimatedKey), "the thinking outline holds still")
            }
        }
    }

    @Test
    fun theThinkingGlowFollowsAVoiceOnlyWhileGivenOne() = runComposeUiTest {
        var voice by mutableStateOf<AmbientVoice?>(null)
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalAmbientVoice provides voice) {
                    ChatPanelAmbientGlow(ChatAmbient(AmbientMotionStatus.Idle), AmbientGlowPlacement.Halo, Modifier.size(GlowSize))
                }
            }
        }
        waitForIdle()
        onNodeWithTag(CHAT_AMBIENT_GLOW_TAG).assertDoesNotExist()
        voice = AmbientVoice(AmbientVoiceRole.You, youTintArgb = AMBER, level = { 0.5f })
        waitForIdle()
        onNodeWithTag(CHAT_AMBIENT_GLOW_TAG).assertExists()
        voice = null
        waitForIdle()
        onNodeWithTag(CHAT_AMBIENT_GLOW_TAG).assertDoesNotExist()
    }

    @Test
    fun eachStatePicksItsColourRole() {
        assertEquals(VoiceGlowRole.You, voiceGlowRole(VoiceModeState.Listening))
        assertEquals(VoiceGlowRole.You, voiceGlowRole(VoiceModeState.Hearing))
        assertEquals(VoiceGlowRole.Agent, voiceGlowRole(VoiceModeState.WaitingForReply))
        assertEquals(VoiceGlowRole.Agent, voiceGlowRole(VoiceModeState.Speaking))
        assertEquals(VoiceGlowRole.Error, voiceGlowRole(VoiceModeState.Error(VoiceErrorCode.ENGINE_FAILED)))
        assertEquals(VoiceGlowRole.Idle, voiceGlowRole(VoiceModeState.NeedsSetup(VoiceSetupGap.NO_ENDPOINT)))
        assertEquals(VoiceGlowRole.Idle, voiceGlowRole(VoiceModeState.Off))
    }

    private companion object {
        val PageHeight = 900.dp
        val LastRowHeight = 40.dp
        val GlowSize = 200.dp
        val AMBER: Int = 0xFFE0A458.toInt()
        const val TIMELINE = "voice-outline-test-timeline"
        const val LAST_ROW = "voice-outline-test-last-row"
    }
}
