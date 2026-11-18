package com.letta.mobile.data.voice.session

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.VoiceEndpointSettings
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.consent.EndpointOrigin
import com.letta.mobile.data.voice.consent.VoiceEndpointGrants
import com.letta.mobile.data.voice.endpoint.SpeechTranscript
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.engine.SpeechSegmentId
import com.letta.mobile.data.voice.host.HostVoiceConnectionCandidate
import com.letta.mobile.data.voice.host.HostVoiceEndpoint
import com.letta.mobile.data.voice.host.HostVoiceModels
import com.letta.mobile.data.voice.host.HostVoiceSetupCommand
import com.letta.mobile.data.voice.host.HostVoiceSetupStatus
import com.letta.mobile.data.voice.host.HostVoiceTestResult
import com.letta.mobile.data.voice.host.HostVoiceWire
import com.letta.mobile.data.voice.host.client.FakeHostVoiceLink
import com.letta.mobile.data.voice.host.client.HostVoiceReply
import com.letta.mobile.data.voice.host.client.SharedVoiceSetup
import com.letta.mobile.data.voice.host.client.VoiceFixtures
import com.letta.mobile.data.voice.host.client.VoiceRoute
import com.letta.mobile.data.voice.host.client.reply
import com.letta.mobile.data.voice.local.LocalSpeechRecognizer
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
import com.letta.mobile.data.voice.vad.ear.ScriptedSource
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Engine selection: a session goes through the host when the host advertises the voice-endpoint
 * contract and shares a setup, and straight to this device's own server otherwise (or when the
 * person chose it); through the host the engines use the host and never this device's key or HTTP.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostVoiceRoutingTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val both = VoiceFixtures.case(HostVoiceWire.SETUP_GET, "listening and speaking available")
    private val speakingOnly = VoiceFixtures.case(HostVoiceWire.SETUP_GET, "speaking only")
    private val nothing = VoiceFixtures.case(HostVoiceWire.SETUP_GET, "nothing stored")

    private fun link(status: VoiceFixtureStatus = VoiceFixtureStatus.BOTH, capable: Boolean = true) =
        FakeHostVoiceLink(if (capable) setOf(HostVoiceWire.CAPABILITY) else emptySet()).apply {
            answer = { method, _ ->
                when (method) {
                    HostVoiceWire.SETUP_GET -> reply(caseOf(status).response)
                    else -> HostVoiceReply(false, error = "voice.not_configured")
                }
            }
        }

    private fun caseOf(status: VoiceFixtureStatus) = when (status) {
        VoiceFixtureStatus.BOTH -> both
        VoiceFixtureStatus.SPEAKING_ONLY -> speakingOnly
        VoiceFixtureStatus.NOTHING -> nothing
    }

    @Test
    fun aHostWithASharedSetupIsTheRoute() = runTest {
        val shared = SharedVoiceSetup(link())
        shared.refresh()
        val route = assertIs<VoiceRoute.Host>(shared.route(preferDevice = false))
        assertEquals(EndpointOrigin("https://speech.example.invalid:443"), route.origin)
    }

    @Test
    fun aHostWithoutTheCapabilityOrWithoutASetupLeavesThisDevicesOwnServer() = runTest {
        val old = SharedVoiceSetup(link(capable = false))
        assertEquals(VoiceRoute.Direct, old.resolveRoute(preferDevice = false))
        assertTrue(old.state.value.status == null && !old.state.value.supported)

        val empty = SharedVoiceSetup(link(VoiceFixtureStatus.NOTHING))
        assertEquals(VoiceRoute.Direct, empty.resolveRoute(preferDevice = false))
    }

    @Test
    fun choosingThisDevicesOwnServerWinsOverTheSharedOne() = runTest {
        val shared = SharedVoiceSetup(link())
        shared.refresh()
        assertEquals(VoiceRoute.Direct, shared.route(preferDevice = true))
    }

    @Test
    fun aFailedReadKeepsTheSharedSetupInForce() = runTest {
        val link = link()
        val shared = SharedVoiceSetup(link)
        shared.refresh()
        link.answer = { _, _ -> error("connection reset") }
        assertIs<VoiceRoute.Host>(shared.resolveRoute(preferDevice = false))
    }

    @Test
    fun throughTheHostTheEnginesSpeakThroughItAndNeverOpenThisDevicesHttp() = runTest {
        val host = RecordingEndpoint()
        var engines = 0
        val factory = EndpointVoiceEngineFactory(Audio(), { engines++.let { MockEngine { error("no direct request") } } })
        val status = json.decodeFromJsonElement(HostVoiceSetupStatus.serializer(), both.response.getValue("result"))
        val route = VoiceRoute.Host(host, status, EndpointOrigin("https://speech.example.invalid:443"))
        val request = VoiceEngineRequest(Session, VoiceSetup(), null, { VoiceEndpointGrants() }, backgroundScope, services(), route)

        val set = factory.create(request)
        set.engines.mouth.speak(SpeechSegment(SpeechSegmentId(1), VoiceText("Hello.")))
        runCurrent()

        assertEquals(0, engines, "no HTTP client for this device's server")
        assertEquals(listOf("Hello."), host.spoken)
        set.release()
    }

    @Test
    fun directlyTheEnginesUseThisDevicesServer() = runTest {
        var engines = 0
        val factory = EndpointVoiceEngineFactory(Audio(), { engines++.let { MockEngine { error("not sent in this test") } } })
        val setup = VoiceSetup(endpoint = VoiceEndpointSettings(SpeechEndpointUrl("http://127.0.0.1:8880")))

        factory.create(VoiceEngineRequest(Session, setup, null, { VoiceEndpointGrants() }, backgroundScope)).release()

        assertEquals(1, engines)
    }

    @Test
    fun withASharedSetupAPressStartsWithoutThisDeviceHavingAServer() = runTest {
        val h = RoutedHarness(this, link())
        h.settle()

        assertEquals(VoicePress.STARTED, h.press())
        val route = assertIs<VoiceRoute.Host>(h.requests.single().route)
        assertNull(h.requests.single().apiKey, "this device's key is never handed to host-routed engines")
        assertEquals(VoiceModeState.Listening, h.controller.state.value)
        assertEquals(EndpointOrigin("https://speech.example.invalid:443"), route.origin)
    }

    @Test
    fun listeningThroughTheHostWaitsForThisDevicesAcknowledgement() = runTest {
        val h = RoutedHarness(this, link())
        h.settle()
        assertEquals(VoicePress.OPEN_SETUP, h.press(listensOnServer = true))
        assertEquals(VoiceModeState.NeedsSetup(VoiceSetupGap.NO_DEVICE_ACK), h.controller.state.value)

        h.store.acknowledgeHostAudio(EndpointOrigin("https://speech.example.invalid:443"))
        h.settle()

        assertEquals(VoicePress.STARTED, h.press(listensOnServer = true))
    }

    @Test
    fun aSharedSetupThatCannotListenIsIncompleteForAServerEar() = runTest {
        val h = RoutedHarness(this, link(VoiceFixtureStatus.SPEAKING_ONLY))
        h.settle()

        assertEquals(VoicePress.OPEN_SETUP, h.press(listensOnServer = true))
        assertEquals(VoiceModeState.NeedsSetup(VoiceSetupGap.HOST_INCOMPLETE), h.controller.state.value)
    }

    @Test
    fun withoutTheCapabilityThisDevicesOwnSetupDecidesAsBefore() = runTest {
        val h = RoutedHarness(this, link(capable = false))
        h.settle()

        assertEquals(VoicePress.OPEN_SETUP, h.press())
        assertEquals(VoiceModeState.NeedsSetup(VoiceSetupGap.NO_ENDPOINT), h.controller.state.value)
    }

    private enum class VoiceFixtureStatus { BOTH, SPEAKING_ONLY, NOTHING }

    private fun services() = VoiceSessionServices.standalone(VoiceSetup())

    private class Audio : VoiceAudio {
        override fun microphone(format: AudioFrameFormat): AudioFrameSource = ScriptedSource(emptyList())

        override fun deviceChanges(): Flow<Unit> = emptyFlow()

        override fun speaker(): AudioPlayer = object : AudioPlayer {
            override suspend fun play(chunks: Flow<PcmChunk>, onLevel: (AudioLevel) -> Unit) {
                chunks.collect { }
            }
        }

        override fun localRecognizer(): LocalSpeechRecognizer? = null
    }

    private object Session : VoiceChatSession {
        override val state: StateFlow<VoiceChatState> = MutableStateFlow(VoiceChatState(conversation = VoiceConversationKey("c1")))

        override suspend fun send(text: VoiceText): SentUtterance = SentUtterance(ClientMessageId("m1"))

        override fun stopRun() = Unit
    }

    /** A host endpoint that records what it is asked to speak and speaks nothing. */
    private class RecordingEndpoint : HostVoiceEndpoint {
        val spoken = mutableListOf<String>()

        override suspend fun status(): HostVoiceSetupStatus = error("unused")

        override suspend fun writeSetup(command: HostVoiceSetupCommand): HostVoiceSetupStatus = error("unused")

        override suspend fun clearSetup(expectedRevision: String?): HostVoiceSetupStatus = error("unused")

        override suspend fun testConnection(candidate: HostVoiceConnectionCandidate?): HostVoiceTestResult = error("unused")

        override suspend fun models(): HostVoiceModels = error("unused")

        override suspend fun transcribe(audio: AudioClip, language: com.letta.mobile.data.voice.SpeechLanguage?): SpeechTranscript =
            error("unused")

        override fun synthesize(text: VoiceText): Flow<PcmChunk> {
            spoken += text.value
            return emptyFlow()
        }
    }

    /** The controller with a shared setup over [link]; its factory records each request. */
    private class RoutedHarness(private val test: TestScope, link: FakeHostVoiceLink) {
        val store = VoiceSetupStore(InMemorySecureSettingsStore(), InMemorySecureSettingsStore())
        val requests = mutableListOf<VoiceEngineRequest>()
        private var serverEar = false
        private val log = EngineLog()
        private val factory = object : VoiceEngineFactory {
            override fun create(request: VoiceEngineRequest): VoiceEngineSet {
                requests += request
                return VoiceEngineSet(VoiceEngines(FakeEar(log), FakeResponder(), FakeMouth(log))) {}
            }

            override fun listensOnServer(setup: VoiceSetup): Boolean = serverEar
        }
        val controller = VoiceModeController(
            sessions = VoiceChatSessions { Session },
            store = store,
            engines = factory,
            microphone = object : MicrophoneAccess {
                override fun granted(): Boolean = true

                override fun request(onResult: (Boolean) -> Unit) = onResult(true)
            },
            runtime = VoiceLoopRuntime(test.backgroundScope, StandardTestDispatcher(test.testScheduler)),
            shared = SharedVoiceSetup(link),
        )

        fun settle() = test.runCurrent()

        fun press(listensOnServer: Boolean = false): VoicePress {
            serverEar = listensOnServer
            return controller.press().also { settle() }
        }
    }
}
