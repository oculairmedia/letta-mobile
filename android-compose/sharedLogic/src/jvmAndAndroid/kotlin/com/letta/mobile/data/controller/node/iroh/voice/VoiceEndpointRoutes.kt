package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.AdminRpcRoutes
import com.letta.mobile.data.controller.node.iroh.adminError
import com.letta.mobile.data.voice.endpoint.SpeechHttpEngines
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import com.letta.mobile.data.voice.host.HostVoiceWire
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The voice endpoint v1 routes (`docs/reference/contracts/voice-endpoint-v1.md`): the host keeps one
 * speech server setup for every paired device and calls the server for them. Pass [forHost] to
 * `AdminRpcHostServices.voiceRoutes`; it registers all seven `voice.*` methods and advertises
 * `voice_endpoint_v1`. The transport's capability matrix gates each method before it gets here.
 */
object VoiceEndpointRoutes {
    /**
     * The routes for a host keeping its voice state in [stateDirectory]: the setup in
     * `voice-setup.json` and the speech server key in `voice-speech-key`, a file only the host's user
     * may read. The key is write-only over the wire and never logged.
     */
    fun forHost(stateDirectory: Path, clock: () -> Long = System::currentTimeMillis): AdminRpcRoutes = VoiceRoutes(
        store = VoiceSetupStore(
            FileVoiceSetupPersistence(stateDirectory.resolve(SETUP_FILE)),
            FileVoiceKeyStore(stateDirectory.resolve(KEY_FILE)),
            clock,
        ),
        upstream = KtorVoiceSpeechUpstream(SpeechHttpEngines.create()),
        inFlight = VoiceInFlight(),
        clock = clock,
    )

    private const val SETUP_FILE = "voice-setup.json"
    private const val KEY_FILE = "voice-speech-key"
}

/**
 * Registers the seven methods. A [VoiceRefusal] answers exactly its code; any other failure is the
 * router's fixed internal error. Telemetry carries the method, the outcome code and the duration only:
 * never audio, transcript or reply text, the key or the server's address.
 */
internal class VoiceRoutes(
    store: VoiceSetupStore,
    upstream: VoiceSpeechUpstream,
    inFlight: VoiceInFlight,
    clock: () -> Long,
) : AdminRpcRoutes {
    private val setup = VoiceSetupHandlers(store, upstream, clock)
    private val requests = VoiceRequestHandlers(store, upstream, inFlight)

    override fun register(router: AdminRpcRouter) {
        router.voice(HostVoiceWire.SETUP_GET) { _, _ -> setup.get() }
        router.voice(HostVoiceWire.SETUP_SET) { params, _ -> setup.set(params) }
        router.voice(HostVoiceWire.SETUP_CLEAR) { params, _ -> setup.clear(params) }
        router.voice(HostVoiceWire.SETUP_TEST) { params, _ -> setup.test(params) }
        router.voice(HostVoiceWire.MODELS_LIST) { _, _ -> setup.models() }
        router.voice(HostVoiceWire.TRANSCRIBE) { params, context -> requests.transcribe(params, context) }
        // Valid only on a request stream of its own; on the control channel it is a malformed request.
        router.registerStreamed(requests.speech) { _, _ ->
            VoiceTelemetry.answered(HostVoiceWire.SPEECH, HostVoiceErrorCode.INVALID_REQUEST.wire, 0L)
            adminError(HostVoiceErrorCode.INVALID_REQUEST.wire)
        }
        router.featureCapabilities += HostVoiceWire.CAPABILITY
    }

    private fun AdminRpcRouter.voice(
        method: String,
        handler: suspend (JsonObject?, AdminRpcRequestContext) -> JsonElement,
    ) = registerScoped(method) { params, context ->
        val started = System.nanoTime()
        try {
            handler(params, context).also { VoiceTelemetry.answered(method, SUCCESS, started) }
        } catch (refusal: VoiceRefusal) {
            VoiceTelemetry.answered(method, refusal.code.wire, started)
            adminError(refusal.code.wire)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            VoiceTelemetry.answered(method, INTERNAL, started)
            throw failure
        }
    }

    private companion object {
        const val SUCCESS = "success"
        const val INTERNAL = "internal"
    }
}

/** The only voice route telemetry: which method, how it ended, how long it took. */
internal object VoiceTelemetry {
    private const val TAG = "VoiceHost"
    private const val NANOS_PER_MILLI = 1_000_000L

    fun answered(method: String, outcome: String, startedNanos: Long) {
        val durationMs = if (startedNanos == 0L) 0L else (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        Telemetry.event(TAG, "request.answered", "method" attr method, "outcome" attr outcome, "durationMs" attr durationMs)
    }
}

/** Voice wire JSON: defaults written (the contract's `has_api_key: false`, `segments: []`), nulls omitted. */
internal val voiceJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = true
}
