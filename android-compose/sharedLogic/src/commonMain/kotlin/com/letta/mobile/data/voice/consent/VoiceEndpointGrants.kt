package com.letta.mobile.data.voice.consent

import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceSettings
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlin.jvm.JvmInline

/**
 * The exact destination a grant names: scheme, host and port of a speech endpoint (for example
 * `https://tts.example.net:443`). Paths and credentials are never part of it.
 */
@JvmInline
value class EndpointOrigin(
    /** The normalised origin. */
    val value: String,
) {
    /** Origins derived from endpoint addresses. */
    companion object {
        /** The origin of [url]; null when it is not an http(s) URL. */
        fun of(url: SpeechEndpointUrl): EndpointOrigin? {
            val parsed = runCatching { Url(url.value) }.getOrNull() ?: return null
            val scheme = parsed.protocol
            if (scheme != URLProtocol.HTTP && scheme != URLProtocol.HTTPS) return null
            return EndpointOrigin("${scheme.name}://${parsed.host.lowercase()}:${parsed.port}")
        }
    }
}

/** What a grant lets the app send to its destination. */
enum class VoiceEndpointPayload {
    /** Captured audio, sent for transcription. */
    AUDIO_FOR_TRANSCRIPTION,

    /** Reply text, sent for synthesis. */
    TEXT_FOR_SYNTHESIS,
}

/** The person's recorded consent to send one kind of [payload] to one [origin]. */
data class VoiceEndpointGrant(
    /** The destination. */
    val origin: EndpointOrigin,
    /** What may be sent there. */
    val payload: VoiceEndpointPayload,
)

/**
 * Every destination grant the person recorded.
 *
 * Kept in its own stored value, apart from [VoiceSettings], so a settings export or backup never
 * carries consent to another device. Endpoint engines check it before every request and fail with
 * [VoiceErrorCode.PERMISSION_REQUIRED] when it is missing; they never fall back to another
 * destination.
 */
data class VoiceEndpointGrants(
    /** The recorded grants. */
    val grants: Set<VoiceEndpointGrant> = emptySet(),
) {
    /** True when [payload] may be sent to the endpoint at [url]. */
    fun allows(url: SpeechEndpointUrl, payload: VoiceEndpointPayload): Boolean {
        val origin = EndpointOrigin.of(url) ?: return false
        return VoiceEndpointGrant(origin, payload) in grants
    }

    /** These grants plus [grant]. */
    fun with(grant: VoiceEndpointGrant): VoiceEndpointGrants = copy(grants = grants + grant)

    /** These grants without [grant]. */
    fun without(grant: VoiceEndpointGrant): VoiceEndpointGrants = copy(grants = grants - grant)
}

/** Reads the current destination grants; the host backs it with its own stored value. */
fun interface VoiceEndpointGrantSource {
    /** The grants recorded right now. */
    fun current(): VoiceEndpointGrants
}
