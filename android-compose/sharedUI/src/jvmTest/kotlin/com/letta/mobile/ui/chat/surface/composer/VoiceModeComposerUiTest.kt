@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceMouth
import com.letta.mobile.data.voice.engine.VoiceMouthEvent
import com.letta.mobile.data.voice.engine.VoiceReply
import com.letta.mobile.data.voice.engine.VoiceResponder
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.loop.VoiceEngines
import com.letta.mobile.data.voice.loop.VoiceLoopRuntime
import com.letta.mobile.data.voice.responder.ClientMessageId
import com.letta.mobile.data.voice.responder.SentUtterance
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.VoiceChatState
import com.letta.mobile.data.voice.responder.VoiceConversationKey
import com.letta.mobile.data.voice.session.MicrophoneAccess
import com.letta.mobile.data.voice.session.VoiceChatSessions
import com.letta.mobile.data.voice.session.VoiceEngineFactory
import com.letta.mobile.data.voice.session.VoiceEngineSet
import com.letta.mobile.data.voice.session.VoiceModeController
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.data.voice.session.VoiceSetupDraft
import com.letta.mobile.data.voice.session.VoiceSetupStore
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.chat.voice.LocalVoiceSetupPane
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.chat.voice.VoiceSetupPaneHost
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** An ear that opens and hears nothing: no microphone is touched. */
private class QuietEar : VoiceEar {
    override fun listen(): Flow<VoiceEarEvent> = flow {
        emit(VoiceEarEvent.ListeningStarted)
        awaitCancellation()
    }

    override fun commitTurn() = Unit
}

/** A mouth that never plays anything. */
private class QuietMouth : VoiceMouth {
    override val events: Flow<VoiceMouthEvent> = emptyFlow()

    override fun speak(segment: SpeechSegment) = Unit

    override fun flush() = Unit

    override suspend fun stop() = Unit
}

/** Nobody is asked anything in these tests. */
private object NoResponder : VoiceResponder {
    override fun respond(utterance: VoiceUtterance): VoiceReply = error("no turn is taken in these tests")
}

private class StillSession : VoiceChatSession {
    override val state: StateFlow<VoiceChatState> = MutableStateFlow(VoiceChatState(conversation = VoiceConversationKey("c1")))

    override suspend fun send(text: VoiceText): SentUtterance = SentUtterance(ClientMessageId("m1"))

    override fun stopRun() = Unit
}

/**
 * The chat bar's voice mode button and status strip: present in every composer, toggled by a tap,
 * opening the Voice settings when the setup is missing, and never moving Send or squeezing the
 * prompt field out of use, at the phone, tablet and desktop widths.
 */
class VoiceModeComposerUiTest {
    private val drafted = ChatComposerUiState(text = "ready", canSend = true)

    @Composable
    private fun rememberVoice(configured: Boolean): VoiceModeHandle {
        val scope = rememberCoroutineScope()
        return remember {
            val store = VoiceSetupStore(InMemorySecureSettingsStore(), InMemorySecureSettingsStore())
            if (configured) store.save(VoiceSetupDraft("http://127.0.0.1:8880", consent = true))
            val engines = VoiceEngineFactory { VoiceEngineSet(VoiceEngines(QuietEar(), NoResponder, QuietMouth())) {} }
            val runtime = VoiceLoopRuntime(scope, Dispatchers.Default)
            VoiceModeHandle(VoiceModeController(VoiceChatSessions { StillSession() }, store, engines, MicrophoneAccess.Granted, runtime))
        }
    }

    /** The composer at [width] in [style], with voice mode while [withVoice] says so. */
    private fun ComposeUiTest.showComposer(
        width: Dp,
        style: LettaInput,
        configured: Boolean = true,
        withVoice: () -> Boolean = { true },
        onHandle: (VoiceModeHandle) -> Unit = {},
    ) = setContent {
        val handle = rememberVoice(configured).also(onHandle)
        MaterialTheme {
            CompositionLocalProvider(
                LocalLettaHost provides LettaHost(input = style),
                LocalVoiceMode provides handle.takeIf { withVoice() },
                LocalVoiceSetupPane provides { _ -> Text("voice setup pane") },
            ) {
                Box(Modifier.width(width)) {
                    ChatComposerPanel(ComposerInputs(composer = drafted, actions = RecordingChatActions()))
                }
                VoiceSetupPaneHost(handle.takeIf { withVoice() })
            }
        }
    }

    private fun ComposeUiTest.bounds(tag: String): DpRect = onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()

    private fun DpRect.toRect(): Rect = Rect(left.value, top.value, right.value, bottom.value)

    private fun DpRect.overlaps(other: DpRect): Boolean = toRect().overlaps(other.toRect())

    private fun DpRect.contains(other: DpRect): Boolean =
        other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom

    private fun ComposeUiTest.waitForState(handle: () -> VoiceModeHandle?, wanted: VoiceModeState) =
        waitUntil(timeoutMillis = 5_000) { handle()?.controller?.state?.value == wanted }

    /** Adds the button to the bar and checks the rest of the bar keeps its place. */
    private fun keepsTheBar(width: Dp, style: LettaInput, bar: String, minimumField: Dp) = runComposeUiTest {
        var voice by mutableStateOf(false)
        showComposer(width, style, withVoice = { voice })
        onNodeWithTag(VoiceModeTags.BUTTON).assertDoesNotExist()
        val sendBefore = bounds(ComposerTestTags.SEND)
        val fieldBefore = bounds(ComposerTestTags.INPUT)

        voice = true
        waitForIdle()
        val button = bounds(VoiceModeTags.BUTTON)
        val send = bounds(ComposerTestTags.SEND)
        val field = bounds(ComposerTestTags.INPUT)
        assertEquals(sendBefore, send, "Send keeps its place at $width ($style)")
        assertTrue(bounds(bar).contains(button), "the button sits inside the bar at $width ($style)")
        assertFalse(button.overlaps(send), "the button stays clear of Send at $width ($style)")
        assertFalse(button.overlaps(field), "the button stays clear of the field at $width ($style)")
        assertTrue(field.left == fieldBefore.left, "the field keeps its start at $width ($style)")
        assertTrue(field.width >= minimumField, "the field keeps ${field.width} >= $minimumField at $width ($style)")
    }

    @Test
    fun thePhoneBarKeepsAUsableFieldAt360() = keepsTheBar(360.dp, LettaInput.Touch, ComposerTestTags.TOUCH_BAR, minimumField = 150.dp)

    @Test
    fun theTouchBarKeepsItsControlsAt700() = keepsTheBar(700.dp, LettaInput.Touch, ComposerTestTags.TOUCH_BAR, minimumField = 400.dp)

    @Test
    fun thePointerCardKeepsItsControlsAt700() = keepsTheBar(700.dp, LettaInput.Pointer, ComposerTestTags.CARD, minimumField = 400.dp)

    @Test
    fun thePointerCardKeepsItsControlsAt760() = keepsTheBar(760.dp, LettaInput.Pointer, ComposerTestTags.CARD, minimumField = 450.dp)

    @Test
    fun thePointerCardKeepsItsControlsAt1200() = keepsTheBar(1200.dp, LettaInput.Pointer, ComposerTestTags.CARD, minimumField = 450.dp)

    @Test
    fun aTapTurnsVoiceModeOnAndASecondTapTurnsItOff() = runComposeUiTest {
        var handle: VoiceModeHandle? = null
        showComposer(360.dp, LettaInput.Touch, onHandle = { handle = it })
        onNodeWithTag(VoiceModeTags.BUTTON)
            .assertContentDescriptionEquals("Start voice conversation")
            .assert(stateDescription("Off"))
        onNodeWithTag(VoiceModeTags.STRIP).assertDoesNotExist()

        onNodeWithTag(VoiceModeTags.BUTTON).performClick()
        waitForState({ handle }, VoiceModeState.Listening)
        onNodeWithTag(VoiceModeTags.BUTTON)
            .assertContentDescriptionEquals("Stop voice conversation")
            .assert(stateDescription("On, listening"))
        onNodeWithText("Listening…").assertExists()

        onNodeWithTag(VoiceModeTags.BUTTON).performClick()
        waitForState({ handle }, VoiceModeState.Off)
        waitForIdle()
        onNodeWithTag(VoiceModeTags.STRIP).assertDoesNotExist()
        onNodeWithTag(VoiceModeTags.BUTTON).assertContentDescriptionEquals("Start voice conversation")
    }

    @Test
    fun theStripsCloseStopsVoiceMode() = runComposeUiTest {
        var handle: VoiceModeHandle? = null
        showComposer(1200.dp, LettaInput.Pointer, onHandle = { handle = it })
        onNodeWithTag(VoiceModeTags.BUTTON).performClick()
        waitForState({ handle }, VoiceModeState.Listening)
        onNodeWithTag(VoiceModeTags.STRIP_CLOSE).performClick()
        waitForState({ handle }, VoiceModeState.Off)
    }

    @Test
    fun withoutASetupATapOpensTheVoiceSettingsAndStartsNothing() = runComposeUiTest {
        var handle: VoiceModeHandle? = null
        showComposer(360.dp, LettaInput.Touch, configured = false, onHandle = { handle = it })
        onNodeWithTag(VoiceModeTags.BUTTON).performClick()
        waitForIdle()
        onNodeWithText("voice setup pane").assertExists()
        onNodeWithText("Voice mode needs a speech server and your consent.").assertExists()
        onNodeWithTag(VoiceModeTags.BUTTON).assert(stateDescription("Needs setup"))
        assertFalse(handle!!.controller.state.value.active)
    }

    private fun stateDescription(text: String): SemanticsMatcher =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)
}
