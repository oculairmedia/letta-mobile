package com.letta.mobile.data.voice.session

import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.VoiceEndpointSettings
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.consent.EndpointOrigin
import com.letta.mobile.data.voice.consent.VoiceEndpointGrant
import com.letta.mobile.data.voice.consent.VoiceEndpointGrants
import com.letta.mobile.data.voice.consent.VoiceEndpointPayload
import com.letta.mobile.data.voice.endpoint.SpeechApiKey
import com.letta.mobile.data.voice.endpoint.SpeechEndpointAddress
import com.letta.mobile.data.voice.endpoint.SpeechEndpointClient
import com.letta.mobile.data.voice.endpoint.SpeechEndpointConfig
import com.letta.mobile.data.voice.endpoint.SpeechEndpointException
import com.letta.mobile.data.voice.endpoint.SpeechEndpointLocality
import com.letta.mobile.data.voice.endpoint.SpeechSpeed
import com.letta.mobile.data.voice.endpoint.SpeechTransportPolicy
import com.letta.mobile.data.voice.host.client.VoiceRoute
import com.letta.mobile.data.voice.vad.EndOfTurnPolicy
import com.letta.mobile.data.voice.vad.ear.EarlyTurns
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The person's voice-mode tuning: how fast replies are spoken, how long a pause ends their turn, and
 * whether the server ear shows their words live.
 */
data class VoiceTuning(
    /** The speed replies are spoken at, sent to the speech endpoint. */
    val speechSpeed: SpeechSpeed = SpeechSpeed.Normal,
    /** The silence that ends a turn for the server ear (the device's recogniser decides its own). */
    val endOfTurnSilence: Duration = EndOfTurnPolicy.DEFAULT_TRAILING_SILENCE,
    /** Whether the server ear requests live partial transcripts; null follows the endpoint ([VoiceSetup.livePartials]). */
    val livePartials: Boolean? = null,
    /**
     * After this pause the server ear offers the turn's words before the end-of-turn silence, so
     * the agent starts sooner; the answer plays only once the turn ends with the same words, and
     * is dropped if the person goes on. Null (the default) waits for the end of the turn: an offer
     * the person talks past still reaches the conversation as a message whose run is stopped.
     */
    val earlyTurnPause: Duration? = null,
)

/** What the person configured for voice mode, as stored. Never carries the API key itself. */
data class VoiceSetup(
    /** The speech endpoint and its model names. */
    val endpoint: VoiceEndpointSettings = VoiceEndpointSettings(),
    /** The destination grants the person recorded. */
    val grants: VoiceEndpointGrants = VoiceEndpointGrants(),
    /** Whether an API key is kept in the secure store. */
    val hasApiKey: Boolean = false,
    /**
     * The person asked to interrupt replies by speaking even though the microphone is not
     * echo-cancelled (they use headphones). Where it is echo-cancelled, speaking always interrupts.
     */
    val interruptBySpeaking: Boolean = false,
    /** Which ear voice mode listens with. */
    val listening: VoiceListening = VoiceListening.AUTOMATIC,
    /** Speech speed and end-of-turn silence. */
    val tuning: VoiceTuning = VoiceTuning(),
    /**
     * The person chose this device's own speech server even where the host shares one; ignored
     * while this device has none.
     */
    val deviceOnly: Boolean = false,
    /**
     * The shared server origin this device acknowledged sending its microphone audio to, once, when
     * it listens through the host; null when it has not. Never exported, like the grants.
     */
    val hostAudioAck: EndpointOrigin? = null,
) {
    /**
     * Whether the server ear transcribes the turn so far while the person speaks (live partial
     * transcripts, several requests per turn): the stored choice, or by default on only when the
     * endpoint is on this machine or a private network (no per-request cost, nothing leaves it).
     */
    val livePartials: Boolean
        get() = tuning.livePartials ?: (endpoint.baseUrl?.let(SpeechEndpointLocality::isLocal) == true)

    /** Whether speaking over a reply interrupts it, on a microphone path that is [echoCancelled] or not. */
    fun bargeInByVoice(echoCancelled: Boolean): Boolean = echoCancelled || interruptBySpeaking

    /** Whether the person agreed to send both audio and reply text to the configured endpoint. */
    val consented: Boolean
        get() {
            val url = endpoint.baseUrl ?: return false
            return VoiceEndpointPayload.entries.all { grants.allows(url, it) }
        }

    /** Whether this device's own server is chosen over the host's shared one (and there is one). */
    val prefersDevice: Boolean get() = deviceOnly && endpoint.baseUrl != null

    /**
     * The grants a request checks: the recorded ones, plus audio for transcription to the shared
     * server's origin once this device acknowledged it ([hostAudioAck]).
     */
    val effectiveGrants: VoiceEndpointGrants
        get() = hostAudioAck?.let { grants.with(VoiceEndpointGrant(it, VoiceEndpointPayload.AUDIO_FOR_TRANSCRIPTION)) } ?: grants

    /**
     * What is missing before a session on [route] can start; null when it can. [serverEar] says the
     * session listens through the speech server (not the device's own recogniser). Through the host,
     * speaking needs the shared setup's speech to be available, and listening through the server
     * also needs its transcription and this device's acknowledgement for that server's origin; this
     * device's own setup is not consulted. Directly, it is [gap].
     */
    fun gapFor(route: VoiceRoute, serverEar: Boolean): VoiceSetupGap? {
        if (route !is VoiceRoute.Host) return gap
        val status = route.status
        return when {
            !status.speech.available -> VoiceSetupGap.HOST_INCOMPLETE
            !serverEar -> null
            !status.transcription.available -> VoiceSetupGap.HOST_INCOMPLETE
            route.origin == null || hostAudioAck != route.origin -> VoiceSetupGap.NO_DEVICE_ACK
            else -> null
        }
    }

    /** What is still missing before voice mode can start on this device's own server; null when it can. */
    val gap: VoiceSetupGap?
        get() {
            val url = endpoint.baseUrl ?: return VoiceSetupGap.NO_ENDPOINT
            return when {
                VoiceEndpointRules.problem(url.value, withKey = false) != null -> VoiceSetupGap.INVALID_ENDPOINT
                !consented -> VoiceSetupGap.NO_CONSENT
                else -> null
            }
        }
}

/**
 * What the person is editing in the Voice settings. [apiKey] null keeps the stored key, blank
 * removes it. Its [toString] never prints the key.
 */
class VoiceSetupDraft(
    /** The base URL as typed. */
    val baseUrl: String,
    /** The API key as typed: null keeps the stored one, blank removes it. */
    val apiKey: String? = null,
    /** Whether the consent toggle is on. */
    val consent: Boolean = false,
) {
    override fun toString(): String = "VoiceSetupDraft(keyTyped=${apiKey != null}, consent=$consent)"
}

/** The rules a speech endpoint address must meet, the same ones the endpoint client enforces. */
object VoiceEndpointRules {
    /**
     * Why [url] cannot be used, or null when it can: INVALID_ENDPOINT unless it is a plain http(s)
     * base URL (no query, fragment, user info or whitespace); with [withKey], INSECURE_TRANSPORT
     * unless it is https or plain http to a loopback host, since a key must never travel unencrypted.
     */
    fun problem(url: String, withKey: Boolean): VoiceErrorCode? {
        val address = SpeechEndpointUrl(url.trim())
        if (address.value.isEmpty() || EndpointOrigin.of(address) == null) return VoiceErrorCode.INVALID_ENDPOINT
        val resolved = SpeechEndpointAddress.resolve(address, emptyList()) ?: return VoiceErrorCode.INVALID_ENDPOINT
        return if (withKey && !SpeechTransportPolicy.protectsKey(resolved)) VoiceErrorCode.INSECURE_TRANSPORT else null
    }
}

/**
 * Voice mode's stored setup: the endpoint address and the destination grants in [settings], each in
 * its own value, and the API key only in [secrets], the store the app keeps server tokens in. With
 * no [secrets] ([keepsApiKey] false) a key is never stored anywhere. Nothing here logs.
 */
class VoiceSetupStore(
    private val settings: SecureSettingsStore,
    private val secrets: SecureSettingsStore?,
) {
    private val state = MutableStateFlow(read())

    /** The stored setup, updated by every [save]. */
    val setup: StateFlow<VoiceSetup> = state.asStateFlow()

    /** Whether this platform can keep an API key (in its secure store). */
    val keepsApiKey: Boolean get() = secrets != null

    /** The stored key, read only to build a request. */
    fun apiKey(): SpeechApiKey? = storedKey()?.let(::SpeechApiKey)

    /**
     * Validates and stores [draft]: the address, a grant for both payloads to its origin when consent
     * is on (and none otherwise, so a changed address never inherits consent), and the key. Returns
     * the address problem and stores nothing when there is one.
     */
    fun save(draft: VoiceSetupDraft): VoiceErrorCode? {
        val key = keyAfter(draft)
        VoiceEndpointRules.problem(draft.baseUrl, withKey = key != null)?.let { return it }
        val url = SpeechEndpointUrl(draft.baseUrl.trim())
        settings.putString(KEY_BASE_URL, url.value)
        val origin = EndpointOrigin.of(url)
        val consented = if (draft.consent) origin else null
        val grants = consented?.let { granted -> VoiceEndpointPayload.entries.map { VoiceEndpointGrant(granted, it) } }.orEmpty()
        settings.putString(KEY_GRANTS, grants.joinToString(GRANT_SEPARATOR) { "${it.payload.name}$FIELD_SEPARATOR${it.origin.value}" })
        secrets.writeApiKey(draft.apiKey)
        state.value = read()
        return null
    }

    /**
     * Whether [draft]'s endpoint answers, checked through the endpoint client without sending audio
     * or text (so it needs no consent): null when it does, else the address problem or the failure
     * code (UNREACHABLE, TIMED_OUT, UNAUTHORIZED, INSECURE_TRANSPORT). Uses and then closes [engine].
     */
    suspend fun check(draft: VoiceSetupDraft, engine: HttpClientEngine): VoiceErrorCode? {
        val key = keyAfter(draft)
        VoiceEndpointRules.problem(draft.baseUrl, withKey = key != null)?.let {
            engine.close()
            return it
        }
        val config = SpeechEndpointConfig(SpeechEndpointUrl(draft.baseUrl.trim()), key?.let(::SpeechApiKey))
        val client = SpeechEndpointClient(engine, config)
        return try {
            client.checkReachable()
            null
        } catch (failure: SpeechEndpointException) {
            failure.code
        } finally {
            client.close()
            engine.close()
        }
    }

    /** Stores whether to interrupt replies by speaking without echo cancellation; takes effect from the next start. */
    fun setInterruptBySpeaking(enabled: Boolean) {
        settings.putString(KEY_INTERRUPT_BY_SPEAKING, enabled.toString())
        state.value = read()
    }

    /** Stores which ear voice mode listens with; takes effect from the next start. */
    fun setListening(listening: VoiceListening) {
        settings.putString(KEY_LISTENING, listening.name)
        state.value = read()
    }

    /** Stores the speed replies are spoken at; takes effect from the next segment. */
    fun setSpeechSpeed(speed: SpeechSpeed) {
        settings.putString(KEY_SPEECH_SPEED, speed.value.toString())
        state.value = read()
    }

    /** Whether the server ear shows the person's words live; see [VoiceSetup.livePartials]. */
    val livePartials: Boolean get() = state.value.livePartials

    /** Stores whether to request live partial transcripts; null returns to the endpoint-based default. Takes effect from the next start. */
    fun setLivePartials(enabled: Boolean?) {
        if (enabled == null) settings.remove(KEY_LIVE_PARTIALS) else settings.putString(KEY_LIVE_PARTIALS, enabled.toString())
        state.value = read()
    }

    /** Stores whether to use this device's own speech server even where the host shares one; from the next start. */
    fun setDeviceOnly(enabled: Boolean) {
        settings.putString(KEY_DEVICE_ONLY, enabled.toString())
        state.value = read()
    }

    /**
     * Records this device's one-time acknowledgement that its microphone audio may go to the shared
     * speech server at [origin]; null withdraws it. Takes effect at the next request.
     */
    fun acknowledgeHostAudio(origin: EndpointOrigin?) {
        if (origin == null) settings.remove(KEY_HOST_AUDIO_ACK) else settings.putString(KEY_HOST_AUDIO_ACK, origin.value)
        state.value = read()
    }

    /**
     * Removes this device's own speech server: its address, grants and key; with [keyOnly], only the
     * key (it moved to the host) and the address and grants stay. Tuning and choices stay.
     */
    fun clearEndpoint(keyOnly: Boolean = false) {
        if (!keyOnly) {
            settings.remove(KEY_BASE_URL)
            settings.remove(KEY_GRANTS)
        }
        secrets?.remove(KEY_API_KEY)
        state.value = read()
    }

    /**
     * Stores the pause after which the server ear offers a turn's words early, clamped to the
     * allowed range; null turns early offers off. Takes effect from the next start.
     */
    fun setEarlyTurnPause(pause: Duration?) {
        if (pause == null) {
            settings.remove(KEY_EARLY_TURN_PAUSE_MS)
        } else {
            val clamped = pause.coerceIn(EarlyTurns.MIN_PAUSE, EndOfTurnPolicy.MAX_TRAILING_SILENCE)
            settings.putString(KEY_EARLY_TURN_PAUSE_MS, clamped.inWholeMilliseconds.toString())
        }
        state.value = read()
    }

    /** Stores the silence that ends a turn, clamped to the allowed range; takes effect from the next start. */
    fun setEndOfTurnSilence(silence: Duration) {
        val clamped = silence.coerceIn(EndOfTurnPolicy.MIN_TRAILING_SILENCE, EndOfTurnPolicy.MAX_TRAILING_SILENCE)
        settings.putString(KEY_END_OF_TURN_SILENCE_MS, clamped.inWholeMilliseconds.toString())
        state.value = read()
    }

    /** The key a [draft] would leave stored: the typed one, none when blanked, else the stored one. */
    fun keyAfter(draft: VoiceSetupDraft): String? = when {
        secrets == null -> null
        draft.apiKey == null -> storedKey()
        else -> draft.apiKey.trim().takeIf { it.isNotEmpty() }
    }


    private fun storedKey(): String? = secrets?.getString(KEY_API_KEY)?.takeIf { it.isNotBlank() }

    private fun read(): VoiceSetup = VoiceSetup(
        endpoint = VoiceEndpointSettings(baseUrl = settings.getString(KEY_BASE_URL)?.takeIf { it.isNotBlank() }?.let(::SpeechEndpointUrl)),
        grants = VoiceEndpointGrants(parseGrants(settings.getString(KEY_GRANTS).orEmpty())),
        hasApiKey = storedKey() != null,
        interruptBySpeaking = settings.getString(KEY_INTERRUPT_BY_SPEAKING) == true.toString(),
        listening = VoiceListening.entries.firstOrNull { it.name == settings.getString(KEY_LISTENING) } ?: VoiceListening.AUTOMATIC,
        tuning = readTuning(settings),
        deviceOnly = settings.getString(KEY_DEVICE_ONLY) == true.toString(),
        hostAudioAck = settings.getString(KEY_HOST_AUDIO_ACK)?.takeIf { it.isNotBlank() }?.let(::EndpointOrigin),
    )

    /** The stored keys. */
    companion object {
        /** The endpoint's base URL. */
        const val KEY_BASE_URL: String = "voice.endpoint.baseUrl"

        /** The destination grants, kept apart from the settings so an export never carries consent. */
        const val KEY_GRANTS: String = "voice.endpoint.grants"

        /** The API key; hosts must register it as a sensitive key of their secure store. */
        const val KEY_API_KEY: String = "voice.endpoint.apiKey"

        /** Whether to interrupt replies by speaking on a microphone without echo cancellation. */
        const val KEY_INTERRUPT_BY_SPEAKING: String = "voice.interruptBySpeaking"

        /** Which ear voice mode listens with ([VoiceListening]). */
        const val KEY_LISTENING: String = "voice.listening"

        /** The speech speed multiplier ([SpeechSpeed]). */
        const val KEY_SPEECH_SPEED: String = "voice.speechSpeed"

        /** The server ear's end-of-turn silence, in milliseconds. */
        const val KEY_END_OF_TURN_SILENCE_MS: String = "voice.endOfTurnSilenceMs"

        /** Whether to request live partial transcripts; absent follows the endpoint. */
        const val KEY_LIVE_PARTIALS: String = "voice.livePartials"

        /** The pause after which the server ear offers a turn's words early, in milliseconds; absent is off. */
        const val KEY_EARLY_TURN_PAUSE_MS: String = "voice.earlyTurnPauseMs"

        /** Whether to use this device's own speech server even where the host shares one. */
        const val KEY_DEVICE_ONLY: String = "voice.endpoint.deviceOnly"

        /** The shared server origin this device acknowledged sending its audio to; kept apart like the grants. */
        const val KEY_HOST_AUDIO_ACK: String = "voice.host.audioAck"

    }
}

private const val GRANT_SEPARATOR = "\n"
private const val FIELD_SEPARATOR = " "

/** Stores [typed] as the API key in this secure store (blank removes it, null keeps it); nothing without a store. */
private fun SecureSettingsStore?.writeApiKey(typed: String?) {
    val store = this ?: return
    val key = typed?.trim() ?: return
    if (key.isEmpty()) store.remove(VoiceSetupStore.KEY_API_KEY) else store.putString(VoiceSetupStore.KEY_API_KEY, key)
}

/** The stored speed, end-of-turn silence and live-partials choice in [settings]. */
private fun readTuning(settings: SecureSettingsStore): VoiceTuning {
    val speed = settings.getString(VoiceSetupStore.KEY_SPEECH_SPEED)?.toFloatOrNull()?.let(SpeechSpeed::coerce) ?: SpeechSpeed.Normal
    val silence = settings.getString(VoiceSetupStore.KEY_END_OF_TURN_SILENCE_MS)?.toLongOrNull()?.milliseconds
        ?.coerceIn(EndOfTurnPolicy.MIN_TRAILING_SILENCE, EndOfTurnPolicy.MAX_TRAILING_SILENCE)
    val partials = settings.getString(VoiceSetupStore.KEY_LIVE_PARTIALS)?.toBooleanStrictOrNull()
    val early = settings.getString(VoiceSetupStore.KEY_EARLY_TURN_PAUSE_MS)?.toLongOrNull()?.milliseconds
        ?.coerceIn(EarlyTurns.MIN_PAUSE, EndOfTurnPolicy.MAX_TRAILING_SILENCE)
    return VoiceTuning(speed, silence ?: EndOfTurnPolicy.DEFAULT_TRAILING_SILENCE, partials, early)
}

/** The grants stored as `PAYLOAD origin` lines. */
private fun parseGrants(text: String): Set<VoiceEndpointGrant> = text.split(GRANT_SEPARATOR).mapNotNull { entry ->
    val payload = VoiceEndpointPayload.entries.firstOrNull { entry.startsWith("${it.name}$FIELD_SEPARATOR") }
    payload?.let { VoiceEndpointGrant(EndpointOrigin(entry.removePrefix("${it.name}$FIELD_SEPARATOR")), it) }
}.toSet()
