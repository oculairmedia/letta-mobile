package com.letta.mobile.data.voice.session

import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.device.RecoveringFrameSource
import com.letta.mobile.data.voice.consent.VoiceEndpointGrantSource
import com.letta.mobile.data.voice.endpoint.EndpointSpeechSynthesizer
import com.letta.mobile.data.voice.endpoint.EndpointTranscriber
import com.letta.mobile.data.voice.endpoint.EndpointVoiceMouth
import com.letta.mobile.data.voice.endpoint.MouthOptions
import com.letta.mobile.data.voice.endpoint.SpeechApiKey
import com.letta.mobile.data.voice.endpoint.SpeechEndpointClient
import com.letta.mobile.data.voice.endpoint.SpeechEndpointConfig
import com.letta.mobile.data.voice.endpoint.StreamingSpeechSynthesizer
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.consent.VoiceEndpointGrant
import com.letta.mobile.data.voice.consent.VoiceEndpointPayload
import com.letta.mobile.data.voice.host.client.HostSpeechSynthesizer
import com.letta.mobile.data.voice.host.client.HostSpeechTranscriber
import com.letta.mobile.data.voice.host.client.VoiceRoute
import com.letta.mobile.data.voice.loop.VoiceEngines
import com.letta.mobile.data.voice.output.VoiceSessionControls
import com.letta.mobile.data.voice.responder.VoiceChatResponder
import com.letta.mobile.data.voice.responder.VoiceChatSession
import com.letta.mobile.data.voice.session.telemetry.TelemetryMouth
import com.letta.mobile.data.voice.session.telemetry.TelemetryPlayer
import com.letta.mobile.data.voice.session.telemetry.TelemetryResponder
import com.letta.mobile.data.voice.session.telemetry.TelemetrySynthesizer
import com.letta.mobile.data.voice.session.telemetry.TelemetryTranscriber
import com.letta.mobile.data.voice.session.telemetry.TelemetryVoiceChatSession
import com.letta.mobile.data.voice.session.telemetry.VoiceEarTelemetry
import com.letta.mobile.data.voice.session.telemetry.VoiceTelemetry
import com.letta.mobile.data.voice.vad.EndOfTurnPolicy
import com.letta.mobile.data.voice.vad.ear.EarlyTurns
import com.letta.mobile.data.voice.vad.ear.LivePartials
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
import kotlin.time.TimeSource

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
    /** Where the engines report their decisions, and the controls they follow. */
    internal val services: VoiceSessionServices,
    /** Where speech goes: through the host's shared server, or straight to this device's own. */
    val route: VoiceRoute = VoiceRoute.Direct,
) {
    /**
     * A request whose engines report with their own clock (it starts at the first `mode.started`)
     * and follow controls of their own (unmuted, at the setup's speed).
     */
    constructor(
        session: VoiceChatSession,
        setup: VoiceSetup,
        apiKey: SpeechApiKey?,
        grants: VoiceEndpointGrantSource,
        scope: CoroutineScope,
    ) : this(session, setup, apiKey, grants, scope, VoiceSessionServices.standalone(setup), VoiceRoute.Direct)

    /** Where the engines report their decisions, timed from the session's start. */
    internal val telemetry: VoiceTelemetry get() = services.telemetry

    /** The mutes, speed, warm-up and audio meter the engines follow. */
    internal val controls: VoiceSessionControls get() = services.controls

    override fun toString(): String = "VoiceEngineRequest(keySet=${apiKey != null}, route=$route)"
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

/** What a controller hands every session's engines: its telemetry and its live controls. */
internal class VoiceSessionServices(
    /** Where the engines report their decisions. */
    val telemetry: VoiceTelemetry,
    /** The controls the engines follow. */
    val controls: VoiceSessionControls,
) {
    /** Factories. */
    companion object {
        /** Services of their own, for a request built outside a controller. */
        fun standalone(setup: VoiceSetup): VoiceSessionServices {
            val speed = MutableStateFlow(setup.tuning.speechSpeed)
            return VoiceSessionServices(VoiceTelemetry(), VoiceSessionControls(TimeSource.Monotonic, speed) { speed.value = it })
        }
    }
}

/** Builds the engines for one voice session. */
fun interface VoiceEngineFactory {
    /** The engines for [request]. */
    fun create(request: VoiceEngineRequest): VoiceEngineSet

    /**
     * Whether a session for [setup] would listen through the speech server rather than the device's
     * own recogniser; the default assumes it does.
     */
    fun listensOnServer(setup: VoiceSetup): Boolean = true
}

/**
 * The voice engines over a platform's [audio], speaking and listening through the route the request
 * names ([VoiceEngineRequest.route]).
 *
 * - **Through the host** ([VoiceRoute.Host]): the speech server the host shares with every device,
 *   reached over the host connection. The server ear transcribes each turn (and its live partials)
 *   with `voice.transcribe` after checking this device's own audio acknowledgement; the mouth
 *   streams PCM with `voice.speech`, one request at a time (the host serves one per connection), at
 *   the server's normal speed, naming the session's agent so the host speaks it in the voice core
 *   of that agent's own `voice` memory block. This device's key and HTTP client are never used.
 * - **Directly** ([VoiceRoute.Direct]): this device's own speech server through a fresh HTTP client on
 *   an engine from [httpEngine], closed on release; the mouth keeps two requests in flight and asks
 *   for the stored speed.
 *
 * Either way the ear follows the person's [VoiceListening] setting ([ListeningChoice]): the device's
 * own recogniser ([VoiceAudio.localRecognizer]) through a [LocalVoiceEar], paused while the app
 * speaks unless the recogniser is echo-cancelled, with the server ear as its fallback under
 * Automatic; or a voice-activity ear on the microphone (reopened when a device goes away) that
 * transcribes each turn through the speech server, half-duplex where the microphone is not
 * echo-cancelled and the person has not asked to interrupt by speaking. The agent's chat is the
 * responder, and the mouth plays each segment while it streams in, up to three sentences ahead. The
 * request's controls mute the microphone and the speaker, get each segment's audio length, and warm
 * the speech connection when the person starts speaking; the server ear ends a turn after the stored
 * end-of-turn silence and, when live partials are on, reports the person's words live and offers
 * them early when they already cover a closed turn (and, with an early-turn pause stored, offers a
 * turn's words after that pause; see [EarlyTurns]). Every engine
 * reports its decisions as `VoiceMode` telemetry (states, reasons, ids, counts and levels; never
 * audio, text, the key or the address), and each speech request and playback stream reports its
 * stages (`speech.request.*`, `speech.firstFrame`, `playback.*`), so a reply that is never heard
 * shows where it stopped.
 */
class EndpointVoiceEngineFactory(
    private val audio: VoiceAudio,
    private val httpEngine: () -> HttpClientEngine,
    private val format: AudioFrameFormat = AudioFrameFormat(),
) : VoiceEngineFactory {
    override fun create(request: VoiceEngineRequest): VoiceEngineSet {
        val speech = speechFor(request)
        val throughHost = request.route is VoiceRoute.Host
        val telemetry = request.telemetry
        telemetry.event("speech.route", "route" attr if (throughHost) "host" else "direct")
        val hearing = hearing(request, speech)
        val controls = request.controls
        controls.warmUpWith { speech.synthesizer.warm() }
        val inFlight = if (throughHost) HOST_IN_FLIGHT else DIRECT_IN_FLIGHT
        val options = MouthOptions(maxInFlight = inFlight, speed = controls::speed, meter = controls.meter)
        val speaker = controls.speaker(TelemetryPlayer(audio.speaker(request.scope), telemetry))
        val synthesizer = TelemetrySynthesizer(speech.synthesizer, telemetry)
        val mouth = TelemetryMouth(EndpointVoiceMouth(synthesizer, speaker, request.scope, options), telemetry)
        val session = TelemetryVoiceChatSession(request.session, telemetry)
        val responder = TelemetryResponder(VoiceChatResponder(session), telemetry, session)
        val engines = VoiceEngines(ear = hearing.ear, responder = responder, mouth = mouth)
        return VoiceEngineSet(engines, hearing.echoCancelled, hearing.bargeInByVoice, hearing.handover) {
            hearing.close()
            speech.close()
        }
    }

    override fun listensOnServer(setup: VoiceSetup): Boolean {
        val availability = audio.localRecognizer()?.availability(null) ?: LocalSpeechAvailability.UNAVAILABLE
        return ListeningChoice.resolve(setup.listening, availability) == ListeningRoute.SERVER
    }

    /** The transcriber and synthesizer of the request's route. */
    private fun speechFor(request: VoiceEngineRequest): Speech = when (val route = request.route) {
        is VoiceRoute.Host -> hostSpeech(request, route)
        VoiceRoute.Direct -> directSpeech(request)
    }

    private fun hostSpeech(request: VoiceEngineRequest, route: VoiceRoute.Host): Speech {
        val origin = route.origin
        val acknowledged = {
            origin != null && VoiceEndpointGrant(origin, VoiceEndpointPayload.AUDIO_FOR_TRANSCRIPTION) in request.grants.current().grants
        }
        val transcriber = HostSpeechTranscriber(route.endpoint, acknowledged = acknowledged)
        val partials = request.setup.tuning.livePartialsFor(route.status.baseUrl?.let(::SpeechEndpointUrl))
        return Speech(transcriber, HostSpeechSynthesizer(route.endpoint) { request.session.agentId }, partials) {}
    }

    private fun directSpeech(request: VoiceEngineRequest): Speech {
        val endpoint = request.setup.endpoint
        val url = checkNotNull(endpoint.baseUrl) { "Voice mode started without an endpoint" }
        val engine = httpEngine()
        val client = SpeechEndpointClient(engine, SpeechEndpointConfig(url, request.apiKey))
        val transcriber = SpeechTranscriber.endpoint(EndpointTranscriber(client, request.grants, endpoint))
        val synthesizer = EndpointSpeechSynthesizer(client, request.grants, endpoint, AudioEncoding.WAV)
        return Speech(transcriber, synthesizer, request.setup.livePartials) {
            client.close()
            engine.close()
        }
    }

    /** The ear the setting and the device call for. */
    private fun hearing(request: VoiceEngineRequest, speech: Speech): Hearing {
        val local = audio.localRecognizer()
        val availability = local?.availability(null) ?: LocalSpeechAvailability.UNAVAILABLE
        val route = ListeningChoice.resolve(request.setup.listening, availability)
        request.telemetry.event(
            "ear.chosen",
            "route" attr route.name,
            "setting" attr request.setup.listening.name,
            "availability" attr availability.name,
        )
        if (route == ListeningRoute.SERVER) return serverHearing(request, speech)
        return localHearing(request, speech, local ?: UnavailableRecognizer, route)
    }

    private fun serverHearing(request: VoiceEngineRequest, speech: Speech): Hearing {
        val microphone = microphone(request)
        val echoCancelled = microphone.echoCancelled
        val bargeInByVoice = request.setup.bargeInByVoice(echoCancelled)
        val ear = serverEar(request, speech, microphone, halfDuplex = !bargeInByVoice)
        return Hearing(ear, echoCancelled, bargeInByVoice, null)
    }

    /**
     * The local recogniser's ear. It pauses while the app speaks unless the recogniser is
     * echo-cancelled, so speaking interrupts the reply only then; its fallback (Automatic only) is a
     * server ear that keeps that rule, as the loop was configured for it.
     */
    private fun localHearing(
        request: VoiceEngineRequest,
        speech: Speech,
        local: LocalSpeechRecognizer,
        route: ListeningRoute,
    ): Hearing {
        val echoCancelled = local.echoCancelled
        val fallback = if (route == ListeningRoute.LOCAL_WITH_FALLBACK) {
            VoiceEarFallback { serverEar(request, speech, microphone(request), halfDuplex = !echoCancelled) }
        } else {
            null
        }
        val config = LocalEarConfig(pauseDuringOutput = !echoCancelled, observer = LocalEarTelemetry(request.telemetry))
        val ear = LocalVoiceEar(local, fallback, config)
        return Hearing(ear, echoCancelled, echoCancelled, ear.handover)
    }

    /** The microphone, reopened when a device goes away and silent while muted. */
    private fun microphone(request: VoiceEngineRequest): RecoveringFrameSource =
        RecoveringFrameSource(request.controls.microphone(audio.microphone(format)), deviceChanges = audio.deviceChanges())

    private fun serverEar(
        request: VoiceEngineRequest,
        speech: Speech,
        microphone: RecoveringFrameSource,
        halfDuplex: Boolean,
    ): VadVoiceEar {
        val telemetry = request.telemetry
        val transcriber = TelemetryTranscriber(speech.transcriber, telemetry)
        val policy = EndOfTurnPolicy(trailingSilence = request.setup.tuning.endOfTurnSilence)
        val partials = LivePartials(enabled = speech.livePartials, settle = LivePartials.DEFAULT_SETTLE)
        val early = EarlyTurns(pause = request.setup.tuning.earlyTurnPause)
        val observer = VoiceEarTelemetry(telemetry)
        val earConfig = VadEarConfig(format, policy, observer = observer, halfDuplex = halfDuplex, partials = partials, early = early)
        return VadVoiceEar(microphone, transcriber, earConfig)
    }

    /**
     * One route's speech: how the ear transcribes, how the mouth synthesises, whether live partials
     * are on, and what to close on release.
     */
    private class Speech(
        val transcriber: SpeechTranscriber,
        val synthesizer: StreamingSpeechSynthesizer,
        val livePartials: Boolean,
        val close: () -> Unit,
    )

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

    private companion object {
        /** The host serves one synthesis per connection: the next request starts once the current one completes. */
        const val HOST_IN_FLIGHT = 1
        const val DIRECT_IN_FLIGHT = 2
    }
}
