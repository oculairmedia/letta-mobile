package com.letta.mobile.data.voice.session

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.VoiceEndpointSettings
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.consent.VoiceEndpointGrants
import com.letta.mobile.data.voice.local.FakeLocalRecognizer
import com.letta.mobile.data.voice.local.LocalRecognitionError
import com.letta.mobile.data.voice.local.LocalRecognitionEvent
import com.letta.mobile.data.voice.local.LocalSpeechAvailability
import com.letta.mobile.data.voice.local.LocalSpeechRecognizer
import com.letta.mobile.data.voice.responder.ClientMessageId
import com.letta.mobile.data.voice.responder.SentUtterance
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.VoiceChatState
import com.letta.mobile.data.voice.vad.ear.ScriptedSource
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which ear a session listens with: the setting, the device's recogniser, and the fallback. */
@OptIn(ExperimentalCoroutinesApi::class)
class ListeningSelectionTest {
    private val microphone = ScriptedSource(emptyList())

    private class Audio(private val mic: AudioFrameSource, private val local: LocalSpeechRecognizer?) : VoiceAudio {
        override fun microphone(format: AudioFrameFormat): AudioFrameSource = mic

        override fun deviceChanges(): Flow<Unit> = emptyFlow()

        override fun speaker(): AudioPlayer = object : AudioPlayer {
            override suspend fun play(chunks: Flow<PcmChunk>, onLevel: (AudioLevel) -> Unit) = Unit
        }

        override fun localRecognizer(): LocalSpeechRecognizer? = local
    }

    private object Session : VoiceChatSession {
        override val state: StateFlow<VoiceChatState> = MutableStateFlow(VoiceChatState())

        override suspend fun send(text: VoiceText): SentUtterance = SentUtterance(ClientMessageId("m1"))

        override fun stopRun() = Unit
    }

    /** Builds one session's engines for [listening] and listens with its ear; returns the set. */
    private fun TestScope.listenWith(listening: VoiceListening, local: LocalSpeechRecognizer?): VoiceEngineSet {
        val factory = EndpointVoiceEngineFactory(Audio(microphone, local), { MockEngine { error("no request in these tests") } })
        val setup = VoiceSetup(endpoint = VoiceEndpointSettings(SpeechEndpointUrl("http://127.0.0.1:8880")), listening = listening)
        val set = factory.create(VoiceEngineRequest(Session, setup, null, { VoiceEndpointGrants() }, backgroundScope))
        backgroundScope.launch { set.engines.ear.listen().collect { } }
        runCurrent()
        return set
    }

    @Test
    fun automaticResolvesToLocalWhereTheDeviceHasARecogniser() {
        val route = ListeningChoice::resolve
        assertEquals(ListeningRoute.LOCAL_WITH_FALLBACK, route(VoiceListening.AUTOMATIC, LocalSpeechAvailability.ON_DEVICE))
        assertEquals(ListeningRoute.LOCAL_WITH_FALLBACK, route(VoiceListening.AUTOMATIC, LocalSpeechAvailability.PLATFORM_SERVICE))
        assertEquals(ListeningRoute.SERVER, route(VoiceListening.AUTOMATIC, LocalSpeechAvailability.UNAVAILABLE))
        assertEquals(ListeningRoute.LOCAL_ONLY, route(VoiceListening.LOCAL, LocalSpeechAvailability.UNAVAILABLE))
        assertEquals(ListeningRoute.SERVER, route(VoiceListening.SERVER, LocalSpeechAvailability.ON_DEVICE))
    }

    @Test
    fun automaticListensLocallyAndLeavesTheMicrophoneClosed() = runTest {
        val recognizer = FakeLocalRecognizer()
        val set = listenWith(VoiceListening.AUTOMATIC, recognizer)

        assertEquals(1, recognizer.recognitions.size)
        assertEquals(0, microphone.opens)
        assertFalse(set.bargeInByVoice, "the local recogniser pauses while the app speaks: a press interrupts")
    }

    @Test
    fun automaticFallsBackToTheServerEarWhenTheRecogniserCannotGoOn() = runTest {
        val recognizer = FakeLocalRecognizer()
        val set = listenWith(VoiceListening.AUTOMATIC, recognizer)
        recognizer.current.send(LocalRecognitionEvent.Failed(LocalRecognitionError.LANGUAGE_UNAVAILABLE))
        runCurrent()

        assertEquals(1, microphone.opens)
        assertTrue(set.handover.value != null)
    }

    @Test
    fun automaticWithoutARecogniserUsesTheServerEar() = runTest {
        listenWith(VoiceListening.AUTOMATIC, null)

        assertEquals(1, microphone.opens)
    }

    @Test
    fun localOnlyNeverTouchesTheServer() = runTest {
        val recognizer = FakeLocalRecognizer()
        listenWith(VoiceListening.LOCAL, recognizer)
        recognizer.current.send(LocalRecognitionEvent.Failed(LocalRecognitionError.LANGUAGE_UNAVAILABLE))
        runCurrent()

        assertEquals(0, microphone.opens)
    }

    @Test
    fun localOnlyWithoutARecogniserStillNeverTouchesTheServer() = runTest {
        listenWith(VoiceListening.LOCAL, null)

        assertEquals(0, microphone.opens)
    }

    @Test
    fun serverNeverTouchesTheRecogniser() = runTest {
        val recognizer = FakeLocalRecognizer()
        listenWith(VoiceListening.SERVER, recognizer)

        assertTrue(recognizer.sessions.isEmpty())
        assertEquals(1, microphone.opens)
    }

    @Test
    fun theSettingIsStoredAndAutomaticByDefault() {
        val store = VoiceSetupStore(InMemorySecureSettingsStore(), null)
        assertEquals(VoiceListening.AUTOMATIC, store.setup.value.listening)

        store.setListening(VoiceListening.SERVER)

        assertEquals(VoiceListening.SERVER, store.setup.value.listening)
        val stored = InMemorySecureSettingsStore().also { it.putString(VoiceSetupStore.KEY_LISTENING, "SERVER") }
        assertEquals(VoiceListening.SERVER, VoiceSetupStore(stored, null).setup.value.listening)
    }
}
