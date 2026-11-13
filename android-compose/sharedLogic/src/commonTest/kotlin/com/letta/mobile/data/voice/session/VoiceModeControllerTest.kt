package com.letta.mobile.data.voice.session

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.engine.VoiceReplyEvent
import com.letta.mobile.data.voice.loop.EngineLog
import com.letta.mobile.data.voice.loop.FakeEar
import com.letta.mobile.data.voice.loop.FakeMouth
import com.letta.mobile.data.voice.loop.FakeResponder
import com.letta.mobile.data.voice.loop.VoiceEngines
import com.letta.mobile.data.voice.loop.VoiceLoopRuntime
import com.letta.mobile.data.voice.responder.ClientMessageId
import com.letta.mobile.data.voice.responder.SentUtterance
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.VoiceChatState
import com.letta.mobile.data.voice.responder.VoiceConversationKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/** A chat session whose conversation the test moves. */
private class FakeChatSession : VoiceChatSession {
    val mutable = MutableStateFlow(VoiceChatState(conversation = VoiceConversationKey("c1")))
    var stops = 0

    override val state: StateFlow<VoiceChatState> = mutable

    override suspend fun send(text: VoiceText): SentUtterance = SentUtterance(ClientMessageId("m1"))

    override fun stopRun() {
        stops++
    }
}

/** A permission that answers what the test says, synchronously. */
private class FakeMicrophone(var allowed: Boolean, var answer: Boolean = allowed) : MicrophoneAccess {
    var requests = 0

    override fun granted(): Boolean = allowed

    override fun request(onResult: (Boolean) -> Unit) {
        requests++
        allowed = answer
        onResult(answer)
    }
}

/** The controller over the loop's scripted fakes, on the test scheduler. */
@OptIn(ExperimentalCoroutinesApi::class)
private class ControllerHarness(
    private val test: TestScope,
    microphoneAllowed: Boolean = true,
    bargeInByVoice: Boolean = true,
) {
    val log = EngineLog()
    val ear = FakeEar(log)
    val mouth = FakeMouth(log)
    val responder = FakeResponder()
    val chat = FakeChatSession()
    val microphone = FakeMicrophone(microphoneAllowed)
    val store = VoiceSetupStore(InMemorySecureSettingsStore(), InMemorySecureSettingsStore())
    var creates = 0
    var releases = 0
    private val clock = object : Clock {
        override fun now(): Instant = Instant.fromEpochMilliseconds(test.testScheduler.currentTime)
    }
    val controller = VoiceModeController(
        sessions = VoiceChatSessions { chat },
        store = store,
        engines = VoiceEngineFactory {
            creates++
            VoiceEngineSet(VoiceEngines(ear, responder, mouth), bargeInByVoice = bargeInByVoice) { releases++ }
        },
        microphone = microphone,
        runtime = VoiceLoopRuntime(test.backgroundScope, StandardTestDispatcher(test.testScheduler), clock),
    )

    val state: VoiceModeState get() = controller.state.value

    fun configure(consent: Boolean = true) {
        assertEquals(null, store.save(VoiceSetupDraft("http://127.0.0.1:8880", consent = consent)))
        settle()
    }

    fun settle() = test.runCurrent()

    fun press(): VoicePress = controller.press().also { settle() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceModeControllerTest {
    @Test
    fun withoutAnEndpointAPressOpensSetupAndStartsNothing() = runTest {
        val h = ControllerHarness(this)
        h.settle()
        assertEquals(VoicePress.OPEN_SETUP, h.press())
        assertEquals(VoiceModeState.NeedsSetup(VoiceSetupGap.NO_ENDPOINT), h.state)
        assertEquals(0, h.creates)
        assertEquals(0, h.microphone.requests)
    }

    @Test
    fun withoutConsentAPressOpensSetup() = runTest {
        val h = ControllerHarness(this)
        h.configure(consent = false)
        assertEquals(VoicePress.OPEN_SETUP, h.press())
        assertEquals(VoiceModeState.NeedsSetup(VoiceSetupGap.NO_CONSENT), h.state)
        assertEquals(0, h.creates)
    }

    @Test
    fun completingTheSetupClearsTheSetupPrompt() = runTest {
        val h = ControllerHarness(this)
        h.press()
        h.configure()
        assertEquals(VoiceModeState.Off, h.state)
    }

    @Test
    fun aRefusedMicrophoneSettlesInNeedsPermissionWithNothingOpen() = runTest {
        val h = ControllerHarness(this, microphoneAllowed = false)
        h.configure()
        assertEquals(VoicePress.ASKED_PERMISSION, h.press())
        assertEquals(VoiceModeState.NeedsMicPermission, h.state)
        assertEquals(1, h.microphone.requests)
        assertEquals(0, h.creates)
        assertFalse(h.ear.listening)
    }

    @Test
    fun aGrantedRequestStartsListening() = runTest {
        val h = ControllerHarness(this, microphoneAllowed = false)
        h.microphone.answer = true
        h.configure()
        assertEquals(VoicePress.ASKED_PERMISSION, h.press())
        assertEquals(VoiceModeState.Listening, h.state)
        assertTrue(h.ear.listening)
    }

    @Test
    fun aTurnWalksThroughHearingWaitingAndSpeaking() = runTest {
        val h = ControllerHarness(this)
        h.configure()
        assertEquals(VoicePress.STARTED, h.press())
        assertEquals(VoiceModeState.Listening, h.state)
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.settle()
        assertEquals(VoiceModeState.Hearing, h.state)
        h.ear.say(VoiceText("what time is it"))
        h.settle()
        assertEquals(VoiceModeState.WaitingForReply, h.state)
        h.responder.last.send(VoiceReplyEvent.Text(VoiceText("It is noon. ")))
        h.settle()
        h.mouth.started(SpeechSegmentId(1))
        h.settle()
        assertEquals(VoiceModeState.Speaking, h.state)
        assertEquals(VoicePress.INTERRUPTED, h.press())
        assertTrue("mouth:stop" in h.log.entries, "a press while speaking stops the speaker")
        assertEquals(VoiceModeState.Listening, h.state, "and voice mode listens on")
        assertTrue(h.ear.listening)
        assertEquals(VoicePress.STOPPED, h.press())
        assertEquals(VoiceModeState.Off, h.state)
        assertFalse(h.ear.listening)
    }

    @Test
    fun halfDuplexSpeechOverTheReplyIsIgnoredAndOnlyAPressInterrupts() = runTest {
        val h = ControllerHarness(this, bargeInByVoice = false)
        h.configure()
        h.press()
        assertEquals(false, h.controller.bargeInByVoice.value)
        h.ear.say(VoiceText("what time is it"))
        h.settle()
        h.responder.last.send(VoiceReplyEvent.Text(VoiceText("It is noon. ")))
        h.settle()
        h.mouth.started(SpeechSegmentId(1))
        h.settle()
        assertEquals(VoiceModeState.Speaking, h.state)
        h.ear.hear(VoiceEarEvent.SpeechStarted)
        h.settle()
        assertEquals(VoiceModeState.Speaking, h.state, "the app's own voice through the speakers interrupts nothing")
        assertEquals(0, h.mouth.stops)
        assertEquals(VoicePress.INTERRUPTED, h.press())
        assertEquals(1, h.mouth.stops)
        assertEquals(VoiceModeState.Listening, h.state)
        assertTrue(h.ear.listening)
        assertEquals(1, h.ear.opens(), "voice mode never left: the microphone stayed open")
    }

    @Test
    fun aSecondPressStopsAndReleasesMicrophoneAndSpeaker() = runTest {
        val h = ControllerHarness(this)
        h.configure()
        h.press()
        assertTrue(h.state.active)
        assertEquals(VoicePress.STOPPED, h.press())
        assertEquals(VoiceModeState.Off, h.state)
        assertFalse(h.ear.listening)
        assertEquals(1, h.releases)
    }

    @Test
    fun aConversationChangeStopsVoiceModeWithoutStoppingTheNewConversationsRun() = runTest {
        val h = ControllerHarness(this)
        h.configure()
        h.press()
        h.chat.mutable.value = VoiceChatState(conversation = VoiceConversationKey("c2"))
        h.settle()
        assertEquals(VoiceModeState.Off, h.state)
        assertFalse(h.ear.listening)
        assertEquals(1, h.releases)
        assertEquals(0, h.chat.stops)
    }

    @Test
    fun aConversationCreatedByTheFirstSendDoesNotStopVoiceMode() = runTest {
        val h = ControllerHarness(this)
        h.chat.mutable.value = VoiceChatState(conversation = null)
        h.configure()
        h.press()
        h.chat.mutable.value = VoiceChatState(conversation = VoiceConversationKey("new"))
        h.settle()
        assertEquals(VoiceModeState.Listening, h.state)
        assertTrue(h.ear.listening)
    }

    @Test
    fun aMicrophoneRefusedByTheOsMapsToNeedsPermission() = runTest {
        val h = ControllerHarness(this)
        h.configure()
        h.press()
        h.ear.hear(VoiceEarEvent.Failed(VoiceError(VoiceErrorCode.PERMISSION_REQUIRED, VoicePort.EAR)))
        h.settle()
        assertEquals(VoiceModeState.NeedsMicPermission, h.state)
    }

    @Test
    fun aFailureShowsItsCodeAndRetryListensAgain() = runTest {
        val h = ControllerHarness(this)
        h.configure()
        h.press()
        h.ear.hear(VoiceEarEvent.Failed(VoiceError(VoiceErrorCode.UNREACHABLE, VoicePort.EAR)))
        h.settle()
        assertEquals(VoiceModeState.Error(VoiceErrorCode.UNREACHABLE), h.state)
        h.controller.retry()
        h.settle()
        assertEquals(VoiceModeState.Listening, h.state)
        assertTrue(h.ear.listening)
    }

    @Test
    fun aFailedUtteranceKeepsVoiceModeOnAndRetrySendsItAgain() = runTest {
        val h = ControllerHarness(this)
        h.configure()
        h.press()
        h.ear.say(VoiceText("what time is it"))
        h.settle()
        h.responder.last.send(VoiceReplyEvent.Failed(VoiceError(VoiceErrorCode.NOT_DELIVERED, VoicePort.RESPONDER)))
        h.settle()
        assertEquals(VoiceModeState.TurnFailed(VoiceErrorCode.NOT_DELIVERED, retryable = true), h.state)
        assertTrue(h.state.active)
        assertTrue(h.ear.listening)
        assertEquals(0, h.releases)
        h.controller.retry()
        h.settle()
        assertEquals(2, h.responder.replies.size)
        assertEquals("what time is it", h.responder.last.utterance.text.value)
        assertEquals(VoiceModeState.WaitingForReply, h.state)
    }

    @Test
    fun anUnattributedReplyIsShownWithoutARetryAndANewUtteranceClearsIt() = runTest {
        val h = ControllerHarness(this)
        h.configure()
        h.press()
        h.ear.say(VoiceText("what time is it"))
        h.settle()
        h.responder.last.send(VoiceReplyEvent.Failed(VoiceError(VoiceErrorCode.UNATTRIBUTED, VoicePort.RESPONDER)))
        h.settle()
        assertEquals(VoiceModeState.TurnFailed(VoiceErrorCode.UNATTRIBUTED, retryable = false), h.state)
        h.ear.say(VoiceText("hello?"))
        h.settle()
        assertEquals(VoiceModeState.WaitingForReply, h.state)
    }

    @Test
    fun shutdownReleasesEverythingAndIgnoresLaterPresses() = runTest {
        val h = ControllerHarness(this)
        h.configure()
        h.press()
        h.controller.shutdown()
        assertEquals(VoiceModeState.Off, h.state)
        assertFalse(h.ear.listening)
        assertEquals(1, h.releases)
        assertEquals(VoicePress.IGNORED, h.press())
        assertEquals(1, h.creates)
    }

    @Test
    fun noSessionToSendThroughAsksForAConversation() = runTest {
        val h = ControllerHarness(this)
        h.configure()
        val controller = VoiceModeController(
            sessions = VoiceChatSessions { null },
            store = h.store,
            engines = VoiceEngineFactory { error("not built") },
            microphone = MicrophoneAccess.Granted,
            runtime = VoiceLoopRuntime(backgroundScope, StandardTestDispatcher(testScheduler)),
        )
        controller.press()
        runCurrent()
        assertEquals(VoiceModeState.NeedsConversation, controller.state.value)
    }
}
