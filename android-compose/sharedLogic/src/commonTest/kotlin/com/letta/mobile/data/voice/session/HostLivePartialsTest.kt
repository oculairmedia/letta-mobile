package com.letta.mobile.data.voice.session

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.SpeechLanguage
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.consent.EndpointOrigin
import com.letta.mobile.data.voice.consent.VoiceEndpointGrant
import com.letta.mobile.data.voice.consent.VoiceEndpointGrants
import com.letta.mobile.data.voice.consent.VoiceEndpointPayload
import com.letta.mobile.data.voice.endpoint.SpeechTranscript
import com.letta.mobile.data.voice.endpoint.TranscriptionPurpose
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.host.HostSpeechSetup
import com.letta.mobile.data.voice.host.HostTranscriptionSetup
import com.letta.mobile.data.voice.host.HostVoiceConnectionCandidate
import com.letta.mobile.data.voice.host.HostVoiceEndpoint
import com.letta.mobile.data.voice.host.HostVoiceModels
import com.letta.mobile.data.voice.host.HostVoiceSetupCommand
import com.letta.mobile.data.voice.host.HostVoiceSetupStatus
import com.letta.mobile.data.voice.host.HostVoiceTestResult
import com.letta.mobile.data.voice.host.HostVoiceWire
import com.letta.mobile.data.voice.host.SpeechContext
import com.letta.mobile.data.voice.host.client.VoiceRoute
import com.letta.mobile.data.voice.local.LocalSpeechRecognizer
import com.letta.mobile.data.voice.responder.ClientMessageId
import com.letta.mobile.data.voice.responder.SentUtterance
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.responder.VoiceChatState
import com.letta.mobile.data.voice.vad.Amplitude
import com.letta.mobile.data.voice.vad.VadSignals
import com.letta.mobile.data.voice.vad.ear.ScriptedSource
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The person's words live on a client without its own recogniser (the desktop): its server ear
 * reports partial transcripts when the shared speech server the host names is on the person's own
 * network, a Tailscale name included, and stays quiet for a server out on the internet.
 */
class HostLivePartialsTest {
    private val signals = VadSignals()

    @Test
    fun aSharedServerOnTheTailnetShowsTheWordsLive() = runTest {
        val endpoint = Endpoint()
        val events = listenOnce("https://speech.example-tailnet.ts.net:8443", endpoint)
        val partials = events.filterIsInstance<VoiceEarEvent.PartialTranscript>()
        assertTrue(partials.isNotEmpty(), "live words while the person speaks")
        assertEquals("hello there", (events.last() as VoiceEarEvent.EndOfTurn).utterance.text.value)
        assertTrue(TranscriptionPurpose.LIVE_PARTIAL in endpoint.purposes, "interval partials reach the host as live partials")
        assertEquals(TranscriptionPurpose.TURN, endpoint.purposes.last(), "the whole turn reaches the host as a turn")
    }

    @Test
    fun aSharedServerOnTheInternetSendsNoPartials() = runTest {
        val endpoint = Endpoint()
        val events = listenOnce("https://speech.example.invalid", endpoint)
        assertEquals(emptyList(), events.filterIsInstance<VoiceEarEvent.PartialTranscript>())
        assertEquals(listOf(TranscriptionPurpose.TURN), endpoint.purposes, "only the whole turn, as a turn")
        assertEquals("hello there", (events.last() as VoiceEarEvent.EndOfTurn).utterance.text.value)
    }

    @Test
    fun theDefaultFollowsTheServerWhereverItIsReached() {
        val tuning = VoiceTuning()
        val local = listOf("https://speech.example-tailnet.ts.net:8443", "http://100.101.102.103:8880", "http://127.0.0.1:8880")
        val remote = listOf(
            "https://speech.example.invalid",
            "http://100.128.0.1:8880",
            "https://ts.net.example.invalid",
            "https://notts.net",
        )
        local.forEach { assertTrue(tuning.livePartialsFor(SpeechEndpointUrl(it)), it) }
        remote.forEach { assertTrue(!tuning.livePartialsFor(SpeechEndpointUrl(it)), it) }
        assertTrue(!tuning.livePartialsFor(null), "no server, no partials")
        assertTrue(!VoiceTuning(livePartials = false).livePartialsFor(SpeechEndpointUrl(local.first())), "the stored choice wins")
    }

    /** One spoken turn through a session routed to the host's shared server at [server]; every ear event up to its end. */
    private suspend fun kotlinx.coroutines.test.TestScope.listenOnce(server: String, endpoint: Endpoint): List<VoiceEarEvent> {
        val origin = EndpointOrigin(server)
        val status = HostVoiceSetupStatus(
            contractVersion = HostVoiceWire.CONTRACT_VERSION,
            configured = true,
            baseUrl = server,
            transcription = HostTranscriptionSetup(available = true, model = "whisper-1"),
            speech = HostSpeechSetup(available = true, model = "tts-1", voice = "alloy"),
        )
        val grants = VoiceEndpointGrants(setOf(VoiceEndpointGrant(origin, VoiceEndpointPayload.AUDIO_FOR_TRANSCRIPTION)))
        val route = VoiceRoute.Host(endpoint, status, origin)
        val factory = EndpointVoiceEngineFactory(Audio(spokenTurn()), { MockEngine { error("no direct request") } })
        val services = VoiceSessionServices.standalone(VoiceSetup())
        val request = VoiceEngineRequest(Session, VoiceSetup(), null, { grants }, backgroundScope, services, route)
        val set = factory.create(request)
        val events = mutableListOf<VoiceEarEvent>()
        set.engines.ear.listen().takeWhile { event ->
            events += event
            event !is VoiceEarEvent.EndOfTurn && event !is VoiceEarEvent.Failed
        }.toList()
        set.release()
        return events
    }

    /** A quiet room, two seconds of speech, then the silence that ends the turn, in 512-sample frames. */
    private fun spokenTurn(): List<ShortArray> {
        val audio = signals.noise(1.seconds, VadSignals.BED) + signals.speechLike(2.seconds, Amplitude(8_000)) +
            signals.noise(1500.milliseconds, VadSignals.BED)
        return audio.toList().chunked(FRAME).map { it.toShortArray() }
    }

    private class Audio(private val script: List<ShortArray>) : VoiceAudio {
        override fun microphone(format: AudioFrameFormat): AudioFrameSource = ScriptedSource(script)

        override fun deviceChanges(): Flow<Unit> = emptyFlow()

        override fun speaker(): AudioPlayer = object : AudioPlayer {
            override suspend fun play(chunks: Flow<PcmChunk>, onLevel: (AudioLevel) -> Unit) = chunks.collect { }
        }

        override fun localRecognizer(): LocalSpeechRecognizer? = null
    }

    private object Session : VoiceChatSession {
        override val state: StateFlow<VoiceChatState> = MutableStateFlow(VoiceChatState())

        override suspend fun send(text: VoiceText): SentUtterance = SentUtterance(ClientMessageId("m1"))

        override fun stopRun() = Unit
    }

    /** The host: every transcription, partial or final, hears the same words; each request's purpose is recorded. */
    private class Endpoint : HostVoiceEndpoint {
        val purposes = mutableListOf<TranscriptionPurpose>()

        override suspend fun status(): HostVoiceSetupStatus = error("unused")

        override suspend fun writeSetup(command: HostVoiceSetupCommand): HostVoiceSetupStatus = error("unused")

        override suspend fun clearSetup(expectedRevision: String?): HostVoiceSetupStatus = error("unused")

        override suspend fun testConnection(candidate: HostVoiceConnectionCandidate?): HostVoiceTestResult = error("unused")

        override suspend fun models(): HostVoiceModels = error("unused")

        override suspend fun transcribe(audio: AudioClip, language: SpeechLanguage?, purpose: TranscriptionPurpose): SpeechTranscript {
            purposes += purpose
            return SpeechTranscript(VoiceText("hello there"))
        }

        override fun synthesize(text: VoiceText, context: SpeechContext): Flow<PcmChunk> = emptyFlow()
    }

    private companion object {
        const val FRAME = 512
    }
}
