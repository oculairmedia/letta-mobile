@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
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
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
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
import com.letta.mobile.ui.chat.session.ChatPermissionModeUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceMode
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.chat.voice.LocalVoiceSetupPane
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.chat.voice.VoiceModeTags
import com.letta.mobile.ui.chat.voice.VoiceSetupPaneHost
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.LettaInput
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.TouchComposerDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.abs
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

/** One composer layout: its input idiom, its mode, and the tags of its bar and field. */
private enum class Layout(val style: LettaInput, val mode: ChatSurfaceMode, val bar: String, val field: String) {
    Card(LettaInput.Pointer, ChatSurfaceMode.FullScreen, ComposerTestTags.CARD, ComposerTestTags.INPUT),
    Docked(LettaInput.Pointer, ChatSurfaceMode.Docked, ComposerTestTags.DOCKED_BAR, ComposerTestTags.DOCKED_INPUT),
    Touch(LettaInput.Touch, ChatSurfaceMode.FullScreen, ComposerTestTags.TOUCH_BAR, ComposerTestTags.INPUT),
    TouchDocked(LettaInput.Touch, ChatSurfaceMode.Docked, ComposerTestTags.TOUCH_BAR, ComposerTestTags.DOCKED_INPUT),
}

/**
 * The chat bar's voice mode button and status strip: the button is the first of the trailing
 * actions, directly before Send, in every composer layout; it never moves Send and never runs the
 * prompt field under it, at the phone, tablet and desktop widths. The strip sits on the bar and the
 * permission mode's line under it, and neither moves when voice mode changes.
 */
class VoiceModeComposerUiTest {
    private val drafted = ChatComposerUiState(
        text = "ready",
        canSend = true,
        permissionMode = ChatPermissionModeUiState(selected = AppServerPermissionMode.Unrestricted),
    )

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

    /**
     * The composer at [width] in [style] and [mode], with voice mode while [withVoice] says so,
     * anchored to the bottom of a fixed-height page as the chat page anchors it.
     */
    private fun ComposeUiTest.showComposer(
        width: Dp,
        style: LettaInput,
        mode: ChatSurfaceMode = ChatSurfaceMode.FullScreen,
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
                Box(Modifier.width(width).height(PageHeight), contentAlignment = Alignment.BottomCenter) {
                    ChatComposerPanel(ComposerInputs(composer = drafted, actions = RecordingChatActions(), mode = mode))
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

    private val DpRect.centreY: Dp get() = (top + bottom) / 2

    private fun ComposeUiTest.waitForState(handle: () -> VoiceModeHandle?, wanted: VoiceModeState) =
        waitUntil(timeoutMillis = 5_000) { handle()?.controller?.state?.value == wanted }

    /**
     * Turns voice mode on in [layout] at [width] and checks the button is the first trailing action:
     * inside the slot and the bar, left of Send with one gap, centred with it, clear of the field and
     * of Send, Send where it was, and the field at least [minimumField] wide.
     */
    private fun sitsBeforeSend(width: Dp, layout: Layout, minimumField: Dp) = runComposeUiTest {
        var voice by mutableStateOf(false)
        showComposer(width, layout.style, layout.mode, withVoice = { voice })
        onNodeWithTag(VoiceModeTags.BUTTON).assertDoesNotExist()
        val sendBefore = bounds(ComposerTestTags.SEND)
        val fieldBefore = bounds(layout.field)

        voice = true
        waitForIdle()
        val where = "at $width ($layout)"
        val button = bounds(VoiceModeTags.BUTTON)
        val send = bounds(ComposerTestTags.SEND)
        val field = bounds(layout.field)
        val slot = bounds(ComposerTestTags.TRAILING_ACTIONS)
        assertEquals(sendBefore, send, "Send keeps its bounds $where")
        assertTrue(slot.contains(button), "the button is inside the trailing slot $where")
        assertTrue(slot.contains(send), "Send is inside the trailing slot $where")
        assertTrue(bounds(layout.bar).contains(button), "the button sits inside the bar $where")
        assertTrue(button.right <= send.left, "the button is left of Send $where")
        assertTrue(send.left - button.right <= LettaDimens.Space.sm, "the button sits one gap from Send $where")
        assertTrue(abs((button.centreY - send.centreY).value) < 0.5f, "the button is centred with Send $where")
        assertEquals(send.width, button.width, "the button is Send's size $where")
        assertFalse(button.overlaps(send), "the button stays clear of Send $where")
        assertFalse(button.overlaps(field), "the button stays clear of the field $where")
        assertTrue(field.right <= button.left, "the field ends before the button $where")
        assertEquals(fieldBefore.left, field.left, "the field keeps its start $where")
        assertTrue(field.width >= minimumField, "the field keeps ${field.width} >= $minimumField $where")
    }

    @Test
    fun theCardSeatsItBeforeSendAt360() = sitsBeforeSend(360.dp, Layout.Card, minimumField = 150.dp)

    @Test
    fun theCardSeatsItBeforeSendAt700() = sitsBeforeSend(700.dp, Layout.Card, minimumField = 400.dp)

    @Test
    fun theCardSeatsItBeforeSendAt760() = sitsBeforeSend(760.dp, Layout.Card, minimumField = 450.dp)

    @Test
    fun theCardSeatsItBeforeSendAt1200() = sitsBeforeSend(1200.dp, Layout.Card, minimumField = 450.dp)

    @Test
    fun theDockedBarSeatsItBeforeSendAt360() = sitsBeforeSend(360.dp, Layout.Docked, minimumField = 120.dp)

    @Test
    fun theDockedBarSeatsItBeforeSendAt760() = sitsBeforeSend(760.dp, Layout.Docked, minimumField = 400.dp)

    @Test
    fun theDockedBarSeatsItBeforeSendAt1200() = sitsBeforeSend(1200.dp, Layout.Docked, minimumField = 400.dp)

    @Test
    fun thePhoneBarSeatsItBeforeSendAt360() = sitsBeforeSend(360.dp, Layout.Touch, minimumField = TouchComposerDimens.minFieldWidth)

    @Test
    fun theTouchBarSeatsItBeforeSendAt700() = sitsBeforeSend(700.dp, Layout.Touch, minimumField = 400.dp)

    @Test
    fun theTouchBarSeatsItBeforeSendAt760() = sitsBeforeSend(760.dp, Layout.Touch, minimumField = 450.dp)

    @Test
    fun theTouchBarSeatsItBeforeSendAt1200() = sitsBeforeSend(1200.dp, Layout.Touch, minimumField = 450.dp)

    @Test
    fun theCanvasTouchBarSeatsItBeforeSendAt700() = sitsBeforeSend(700.dp, Layout.TouchDocked, minimumField = 400.dp)

    @Test
    fun thePhoneFieldIsAtLeastTheMinimumBesideTheButtonAt360() {
        // The "+", the voice-mode button and Send beside the field: exactly the minimum at 360.
        assertEquals(TouchComposerDimens.minFieldWidth, touchFieldWidth(360.dp, controls = 3))
        assertEquals(204.dp, touchFieldWidth(360.dp, controls = 2))
    }

    @Test
    fun theCanvasTouchBarAt360OffersVoiceModeInThePlusSheet() = runComposeUiTest {
        var voice by mutableStateOf(false)
        var handle: VoiceModeHandle? = null
        showComposer(360.dp, LettaInput.Touch, ChatSurfaceMode.Docked, withVoice = { voice }, onHandle = { handle = it })
        val sendBefore = bounds(ComposerTestTags.SEND)
        voice = true
        waitForIdle()
        onNodeWithTag(VoiceModeTags.BUTTON).assertDoesNotExist()
        assertEquals(sendBefore, bounds(ComposerTestTags.SEND), "Send keeps its bounds")
        onNodeWithTag(ComposerTestTags.TOUCH_PLUS).performClick()
        waitForIdle()
        onNodeWithText("Voice conversation").assertExists().performClick()
        waitForState({ handle }, VoiceModeState.Listening)
    }

    /** Voice mode on and off in [layout] at [width]: the strip on the bar, and the bar and its under line still. */
    private fun keepsTheStack(width: Dp, layout: Layout, under: String) = runComposeUiTest {
        var handle: VoiceModeHandle? = null
        showComposer(width, layout.style, onHandle = { handle = it })
        val where = "at $width ($layout)"
        onNodeWithTag(VoiceModeTags.STRIP).assertDoesNotExist()
        val barBefore = bounds(layout.bar)
        val underBefore = bounds(under)

        onNodeWithTag(VoiceModeTags.BUTTON).performClick()
        waitForState({ handle }, VoiceModeState.Listening)
        waitForIdle()
        val strip = bounds(VoiceModeTags.STRIP)
        assertEquals(barBefore, bounds(layout.bar), "the bar stays put with the strip up $where")
        assertEquals(underBefore, bounds(under), "the line under the bar stays put $where")
        assertTrue(strip.bottom <= barBefore.top, "the strip sits above the bar $where")
        assertTrue(underBefore.top >= barBefore.bottom || barBefore.contains(underBefore), "the line is under the row $where")

        onNodeWithTag(VoiceModeTags.STRIP_CLOSE).performClick()
        waitForState({ handle }, VoiceModeState.Off)
        waitForIdle()
        assertEquals(barBefore, bounds(layout.bar), "the bar stays put once the strip goes $where")
        assertEquals(underBefore, bounds(under), "the line stays put once the strip goes $where")
    }

    @Test
    fun theStripAndTheModeLineKeepTheCardStillAt1200() =
        keepsTheStack(1200.dp, Layout.Card, ComposerTestTags.PERMISSION_MODE_LINE)

    @Test
    fun theStripAndTheModeLineKeepTheCardStillAt760() =
        keepsTheStack(760.dp, Layout.Card, ComposerTestTags.PERMISSION_MODE_LINE)

    @Test
    fun theStripKeepsThePhoneBarStillAt360() =
        keepsTheStack(360.dp, Layout.Touch, ComposerTestTags.TOUCH_BAR)

    @Test
    fun theStripKeepsTheTouchBarStillAt700() =
        keepsTheStack(700.dp, Layout.Touch, ComposerTestTags.TOUCH_BAR)

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

    private companion object {
        val PageHeight = 900.dp
    }
}
