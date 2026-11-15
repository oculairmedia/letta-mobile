package com.letta.mobile.data.voice.session

import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.device.RecoveringFrameSource
import com.letta.mobile.data.voice.consent.VoiceEndpointGrantSource
import com.letta.mobile.data.voice.endpoint.EndpointSpeechSynthesizer
import com.letta.mobile.data.voice.endpoint.EndpointTranscriber
import com.letta.mobile.data.voice.endpoint.EndpointVoiceMouth
import com.letta.mobile.data.voice.endpoint.SpeechApiKey
import com.letta.mobile.data.voice.endpoint.SpeechEndpointClient
import com.letta.mobile.data.voice.endpoint.SpeechEndpointConfig
import com.letta.mobile.data.voice.loop.VoiceEngines
import com.letta.mobile.data.voice.responder.VoiceChatResponder
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.session.telemetry.TelemetryMouth
import com.letta.mobile.data.voice.session.telemetry.TelemetryResponder
import com.letta.mobile.data.voice.session.telemetry.TelemetryTranscriber
import com.letta.mobile.data.voice.session.telemetry.TelemetryVoiceChatSession
import com.letta.mobile.data.voice.session.telemetry.VoiceEarTelemetry
import com.letta.mobile.data.voice.session.telemetry.VoiceTelemetry
import com.letta.mobile.data.voice.vad.ear.SpeechTranscriber
import com.letta.mobile.data.voice.vad.ear.VadEarConfig
import com.letta.mobile.data.voice.vad.ear.VadVoiceEar
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope

/** What one voice session's engines are built from. Its [toString] carries no address or key. */
class VoiceEngineRequest internal constructor(
    /** The chat the utterances go to and the replies come from. */
    val session: VoiceChatSession,
    /** The stored setup; its endpoint is configured and consented. */
    val setup: VoiceSetup,
    /** The stored API key, if any. */
    val apiKey: SpeechApiKey?,
    /** The live grants, checked before every request (a withdrawn consent takes effect at once). */
    val grants: VoiceEndpointGrantSource,
    /** The session's scope; the mouth's workers run in it. */
    val scope: CoroutineScope,
    /** Where the engines report their decisions, timed from the session's start. */
    internal val telemetry: VoiceTelemetry,
) {
    /** A request whose engines report with their own clock (it starts at the first `mode.started`). */
    constructor(
        session: VoiceChatSession,
        setup: VoiceSetup,
        apiKey: SpeechApiKey?,
        grants: VoiceEndpointGrantSource,
        scope: CoroutineScope,
    ) : this(session, setup, apiKey, grants, scope, VoiceTelemetry())

    override fun toString(): String = "VoiceEngineRequest(keySet=${apiKey != null})"
}

/**
 * One session's engines, and how to let go of what they hold once the loop has shut down; with
 * whether the microphone path is [echoCancelled] and whether these engines let the person
 * interrupt by speaking ([bargeInByVoice]; false means a half-duplex ear that ignores the
 * microphone while the app speaks, so only a press interrupts).
 */
class VoiceEngineSet(
    /** The ear, responder and mouth. */
    val engines: VoiceEngines,
    /** Whether the platform cancels the app's own playback out of the microphone. */
    val echoCancelled: Boolean = false,
    /** Whether speech over the app's output interrupts it. */
    val bargeInByVoice: Boolean = true,
    private val onRelease: () -> Unit,
) {
    /** Releases the engines' resources (the retained capture, the HTTP client). Call once, after the loop's shutdown. */
    fun release() = onRelease()
}

/** Builds the engines for one voice session. */
fun interface VoiceEngineFactory {
    /** The engines for [request]. */
    fun create(request: VoiceEngineRequest): VoiceEngineSet
}

/**
 * The endpoint engines over a platform's [audio]: a voice-activity ear on the microphone (reopened
 * when a device goes away) that transcribes each turn through the endpoint, the agent's chat as the
 * responder, and a mouth that streams WAVE audio from the endpoint and plays it on the
 * speaker. Where the microphone is not echo-cancelled and the person has not asked to interrupt
 * by speaking, the ear is half-duplex. Each session gets its own HTTP client on a fresh engine
 * from [httpEngine], closed on release. Every engine reports its decisions as `VoiceMode`
 * telemetry (states, reasons, ids, counts and levels; never audio, text, the key or the address).
 */
class EndpointVoiceEngineFactory(
    private val audio: VoiceAudio,
    private val httpEngine: () -> HttpClientEngine,
    private val format: AudioFrameFormat = AudioFrameFormat(),
) : VoiceEngineFactory {
    override fun create(request: VoiceEngineRequest): VoiceEngineSet {
        val endpoint = request.setup.endpoint
        val url = checkNotNull(endpoint.baseUrl) { "Voice mode started without an endpoint" }
        val engine = httpEngine()
        val client = SpeechEndpointClient(engine, SpeechEndpointConfig(url, request.apiKey))
        val telemetry = request.telemetry
        val transcriber = TelemetryTranscriber(SpeechTranscriber.endpoint(EndpointTranscriber(client, request.grants, endpoint)), telemetry)
        val microphone = RecoveringFrameSource(audio.microphone(format), deviceChanges = audio.deviceChanges())
        val echoCancelled = microphone.echoCancelled
        val bargeInByVoice = request.setup.bargeInByVoice(echoCancelled)
        val earConfig = VadEarConfig(format, observer = VoiceEarTelemetry(telemetry), halfDuplex = !bargeInByVoice)
        val ear = VadVoiceEar(microphone, transcriber, earConfig)
        val synthesizer = EndpointSpeechSynthesizer(client, request.grants, endpoint, AudioEncoding.WAV)
        val mouth = TelemetryMouth(EndpointVoiceMouth(synthesizer, audio.speaker(), request.scope), telemetry)
        val session = TelemetryVoiceChatSession(request.session, telemetry)
        val responder = TelemetryResponder(VoiceChatResponder(session), telemetry, session)
        val engines = VoiceEngines(ear = ear, responder = responder, mouth = mouth)
        return VoiceEngineSet(engines, echoCancelled, bargeInByVoice) {
            ear.close()
            client.close()
            engine.close()
        }
    }
}
