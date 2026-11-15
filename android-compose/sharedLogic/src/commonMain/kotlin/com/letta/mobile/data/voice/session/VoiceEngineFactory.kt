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
import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.local.LocalEarConfig
import com.letta.mobile.data.voice.local.LocalHandoverReason
import com.letta.mobile.data.voice.local.LocalSpeechAvailability
import com.letta.mobile.data.voice.local.LocalSpeechRecognizer
import com.letta.mobile.data.voice.local.LocalVoiceEar
import com.letta.mobile.data.voice.local.UnavailableRecognizer
import com.letta.mobile.data.voice.local.VoiceEarFallback
import com.letta.mobile.data.voice.session.telemetry.LocalEarTelemetry
import com.letta.mobile.data.voice.session.telemetry.attr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
    /** Why the local ear handed listening over to the server mid-session; null while it has not (or never listened locally). */
    val handover: StateFlow<LocalHandoverReason?> = MutableStateFlow(null),
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
 * The endpoint engines over a platform's [audio]. The ear follows the person's [VoiceListening]
 * setting ([ListeningChoice]): the device's own recogniser ([VoiceAudio.localRecognizer]) through a
 * [LocalVoiceEar], paused while the app speaks unless the recogniser is echo-cancelled, with the
 * server ear as its fallback under Automatic; or a voice-activity ear on the microphone (reopened
 * when a device goes away) that transcribes each turn through the endpoint, half-duplex where the
 * microphone is not echo-cancelled and the person has not asked to interrupt by speaking. The
 * agent's chat is the responder, and the mouth streams WAVE audio from the endpoint and plays it on
 * the speaker. Each session gets its own HTTP client on a fresh engine from [httpEngine], closed on
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
        val telemetry = request.telemetry
        val hearing = hearing(request, client)
        val synthesizer = EndpointSpeechSynthesizer(client, request.grants, endpoint, AudioEncoding.WAV)
        val mouth = TelemetryMouth(EndpointVoiceMouth(synthesizer, audio.speaker(), request.scope), telemetry)
        val session = TelemetryVoiceChatSession(request.session, telemetry)
        val responder = TelemetryResponder(VoiceChatResponder(session), telemetry, session)
        val engines = VoiceEngines(ear = hearing.ear, responder = responder, mouth = mouth)
        return VoiceEngineSet(engines, hearing.echoCancelled, hearing.bargeInByVoice, hearing.handover) {
            hearing.close()
            client.close()
            engine.close()
        }
    }

    /** The ear the setting and the device call for. */
    private fun hearing(request: VoiceEngineRequest, client: SpeechEndpointClient): Hearing {
        val local = audio.localRecognizer()
        val availability = local?.availability(null) ?: LocalSpeechAvailability.UNAVAILABLE
        val route = ListeningChoice.resolve(request.setup.listening, availability)
        request.telemetry.event(
            "ear.chosen",
            "route" attr route.name,
            "setting" attr request.setup.listening.name,
            "availability" attr availability.name,
        )
        if (route == ListeningRoute.SERVER) return serverHearing(request, client)
        return localHearing(request, client, local ?: UnavailableRecognizer, route)
    }

    private fun serverHearing(request: VoiceEngineRequest, client: SpeechEndpointClient): Hearing {
        val microphone = RecoveringFrameSource(audio.microphone(format), deviceChanges = audio.deviceChanges())
        val echoCancelled = microphone.echoCancelled
        val bargeInByVoice = request.setup.bargeInByVoice(echoCancelled)
        val ear = serverEar(request, client, microphone, halfDuplex = !bargeInByVoice)
        return Hearing(ear, echoCancelled, bargeInByVoice, null)
    }

    /**
     * The local recogniser's ear. It pauses while the app speaks unless the recogniser is
     * echo-cancelled, so speaking interrupts the reply only then; its fallback (Automatic only) is a
     * server ear that keeps that rule, as the loop was configured for it.
     */
    private fun localHearing(
        request: VoiceEngineRequest,
        client: SpeechEndpointClient,
        local: LocalSpeechRecognizer,
        route: ListeningRoute,
    ): Hearing {
        val echoCancelled = local.echoCancelled
        val fallback = if (route == ListeningRoute.LOCAL_WITH_FALLBACK) {
            VoiceEarFallback {
                val microphone = RecoveringFrameSource(audio.microphone(format), deviceChanges = audio.deviceChanges())
                serverEar(request, client, microphone, halfDuplex = !echoCancelled)
            }
        } else {
            null
        }
        val config = LocalEarConfig(pauseDuringOutput = !echoCancelled, observer = LocalEarTelemetry(request.telemetry))
        val ear = LocalVoiceEar(local, fallback, config)
        return Hearing(ear, echoCancelled, echoCancelled, ear.handover)
    }

    private fun serverEar(
        request: VoiceEngineRequest,
        client: SpeechEndpointClient,
        microphone: RecoveringFrameSource,
        halfDuplex: Boolean,
    ): VadVoiceEar {
        val endpoint = request.setup.endpoint
        val telemetry = request.telemetry
        val transcriber = TelemetryTranscriber(SpeechTranscriber.endpoint(EndpointTranscriber(client, request.grants, endpoint)), telemetry)
        val earConfig = VadEarConfig(format, observer = VoiceEarTelemetry(telemetry), halfDuplex = halfDuplex)
        return VadVoiceEar(microphone, transcriber, earConfig)
    }

    /** One session's ear and what it means for interrupting. */
    private class Hearing(
        val ear: VoiceEar,
        val echoCancelled: Boolean,
        val bargeInByVoice: Boolean,
        handover: StateFlow<LocalHandoverReason?>?,
    ) {
        val handover: StateFlow<LocalHandoverReason?> = handover ?: MutableStateFlow(null)

        /** Releases what the ear holds (a retained capture, a fallback ear). */
        fun close() {
            (ear as? AutoCloseable)?.close()
        }
    }
}
