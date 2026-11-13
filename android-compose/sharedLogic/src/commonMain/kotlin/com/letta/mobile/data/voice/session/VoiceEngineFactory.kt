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
import com.letta.mobile.data.voice.vad.ear.SpeechTranscriber
import com.letta.mobile.data.voice.vad.ear.VadEarConfig
import com.letta.mobile.data.voice.vad.ear.VadVoiceEar
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope

/** What one voice session's engines are built from. Its [toString] carries no address or key. */
class VoiceEngineRequest(
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
) {
    override fun toString(): String = "VoiceEngineRequest(keySet=${apiKey != null})"
}

/** One session's engines, and how to let go of what they hold once the loop has shut down. */
class VoiceEngineSet(
    /** The ear, responder and mouth. */
    val engines: VoiceEngines,
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
 * responder, and a mouth that synthesises WAVE audio through the endpoint and plays it on the
 * speaker. Each session gets its own HTTP client on a fresh engine from [httpEngine], closed on
 * release. Every engine reports its decisions as `VoiceMode` telemetry (states, reasons, ids,
 * counts and levels; never audio, text, the key or the address).
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
        val transcriber = TelemetryTranscriber(SpeechTranscriber.endpoint(EndpointTranscriber(client, request.grants, endpoint)))
        val microphone = RecoveringFrameSource(audio.microphone(format), deviceChanges = audio.deviceChanges())
        val ear = VadVoiceEar(microphone, transcriber, VadEarConfig(format, observer = VoiceEarTelemetry))
        val synthesizer = EndpointSpeechSynthesizer(client, request.grants, endpoint, AudioEncoding.WAV)
        val mouth = TelemetryMouth(EndpointVoiceMouth(synthesizer, audio.speaker(), request.scope))
        val session = TelemetryVoiceChatSession(request.session)
        val responder = TelemetryResponder(VoiceChatResponder(session)) { session.lastSent }
        val engines = VoiceEngines(ear = ear, responder = responder, mouth = mouth)
        return VoiceEngineSet(engines) {
            ear.close()
            client.close()
            engine.close()
        }
    }
}
