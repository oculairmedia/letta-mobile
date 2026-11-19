package com.letta.mobile.data.voice.host

import com.letta.mobile.data.rpcwire.AdminRpcErrors
import com.letta.mobile.data.voice.VoiceErrorCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The closed set of failures a host voice request can end with. [wire] is the exact `error` string
 * of the failed response; it never carries a speech server's response, address or key, so it is safe
 * to report. [retryable] says whether the same request may succeed if sent again unchanged, and
 * [voiceCode] is the voice loop's own code for it.
 */
@Serializable
enum class HostVoiceErrorCode(
    /** The `error` string on the wire. */
    val wire: String,
    /** Whether sending the same request again may succeed. */
    val retryable: Boolean,
    /** The voice loop's code for this failure. */
    val voiceCode: VoiceErrorCode,
) {
    /** The host has no speech server set up, or none for this kind of request. */
    @SerialName("voice.not_configured")
    NOT_CONFIGURED("voice.not_configured", false, VoiceErrorCode.UNAVAILABLE),

    /** The shared grant for this kind of payload is missing; nothing was sent. */
    @SerialName("voice.consent_required")
    CONSENT_REQUIRED("voice.consent_required", false, VoiceErrorCode.PERMISSION_REQUIRED),

    /** The request was malformed: a missing field, an unsupported encoding, an invalid address. */
    @SerialName("voice.invalid_request")
    INVALID_REQUEST("voice.invalid_request", false, VoiceErrorCode.REJECTED),

    /** The audio or text is over the contract's limits; nothing was sent. */
    @SerialName("voice.payload_too_large")
    PAYLOAD_TOO_LARGE("voice.payload_too_large", false, VoiceErrorCode.REJECTED),

    /** The key would travel unencrypted (plain http to a host other than loopback); nothing was sent. */
    @SerialName("voice.insecure_transport")
    INSECURE_TRANSPORT("voice.insecure_transport", false, VoiceErrorCode.INSECURE_TRANSPORT),

    /** The speech server could not be reached. */
    @SerialName("voice.upstream_unreachable")
    UPSTREAM_UNREACHABLE("voice.upstream_unreachable", true, VoiceErrorCode.UNREACHABLE),

    /**
     * The speech server's host name did not resolve on the host running the request: a name or DNS
     * problem, not a refused connection, so the person can tell the two apart in the app.
     */
    @SerialName("voice.upstream_unresolved")
    UPSTREAM_UNRESOLVED("voice.upstream_unresolved", true, VoiceErrorCode.UNRESOLVED),

    /** The speech server refused the stored key. */
    @SerialName("voice.upstream_unauthorized")
    UPSTREAM_UNAUTHORIZED("voice.upstream_unauthorized", false, VoiceErrorCode.UNAUTHORIZED),

    /** The speech server refused the request (unknown model or voice, bad input). */
    @SerialName("voice.upstream_rejected")
    UPSTREAM_REJECTED("voice.upstream_rejected", false, VoiceErrorCode.REJECTED),

    /** The speech server answered with something that is not the expected shape. */
    @SerialName("voice.upstream_bad_response")
    UPSTREAM_BAD_RESPONSE("voice.upstream_bad_response", true, VoiceErrorCode.BAD_RESPONSE),

    /** The speech server did not answer within its budget. */
    @SerialName("voice.upstream_timeout")
    UPSTREAM_TIMEOUT("voice.upstream_timeout", true, VoiceErrorCode.TIMED_OUT),

    /** Another request of the same kind is in flight for this device, or the host is at its limit. */
    @SerialName("voice.busy")
    BUSY("voice.busy", true, VoiceErrorCode.BUSY),

    /** The setup changed since the revision the write named; read it again before writing. */
    @SerialName("voice.revision_conflict")
    REVISION_CONFLICT("voice.revision_conflict", false, VoiceErrorCode.REJECTED),

    /** The client cancelled the request. */
    @SerialName("voice.cancelled")
    CANCELLED("voice.cancelled", false, VoiceErrorCode.CANCELLED),

    /** This device's pairing lacks the capability the method needs (the transport's own refusal). */
    @SerialName("forbidden")
    NOT_AUTHORIZED("forbidden", false, VoiceErrorCode.PERMISSION_REQUIRED),

    /** The host does not serve this method: it predates the contract. */
    @SerialName("unsupported")
    UNSUPPORTED("unsupported", false, VoiceErrorCode.UNAVAILABLE),

    /** Anything else the host reported, including its own internal errors. */
    @SerialName("internal")
    INTERNAL("internal", true, VoiceErrorCode.ENGINE_FAILED),
    ;

    /** Reading a failed response's `error`. */
    companion object {
        /**
         * The code for a failed response's [error] string: an exact wire code, the transport's
         * refusal or unknown-method error, else [INTERNAL]. Never echoes [error] itself.
         */
        fun fromWire(error: String?): HostVoiceErrorCode = entries.firstOrNull { it.wire == error }
            ?: if (AdminRpcErrors.isUnknownMethod(error)) UNSUPPORTED else INTERNAL
    }
}

/** A host voice request failed with [code]. The message names the code only. */
class HostVoiceException(
    /** Why the request failed. */
    val code: HostVoiceErrorCode,
) : Exception("Host voice request failed: ${code.wire}")
