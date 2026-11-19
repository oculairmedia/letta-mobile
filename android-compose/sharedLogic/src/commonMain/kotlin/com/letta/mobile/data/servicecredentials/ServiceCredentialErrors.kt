package com.letta.mobile.data.servicecredentials

import com.letta.mobile.data.rpcwire.AdminRpcErrors
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The closed set of failures of a service-credentials request or connection test. [wire] is the
 * exact `error` string; it never carries a service's response, address or secret.
 */
@Serializable
enum class ServiceCredentialErrorCode(
    /** The `error` string on the wire. */
    val wire: String,
) {
    /** A missing or malformed field, an unknown config key, or an address that is not plain http(s). */
    @SerialName("service.invalid_request")
    INVALID_REQUEST("service.invalid_request"),

    /** The host knows no service with that id. */
    @SerialName("service.unknown_service")
    UNKNOWN_SERVICE("service.unknown_service"),

    /** Nothing usable is stored for the service: no secret, or no address. */
    @SerialName("service.not_configured")
    NOT_CONFIGURED("service.not_configured"),

    /** The secret would travel unencrypted (plain http to a host other than loopback). */
    @SerialName("service.insecure_transport")
    INSECURE_TRANSPORT("service.insecure_transport"),

    /** The service changed since the revision the write named. */
    @SerialName("service.revision_conflict")
    REVISION_CONFLICT("service.revision_conflict"),

    /** Test outcome: the service refused the secret. */
    @SerialName("service.unauthorized")
    UNAUTHORIZED("service.unauthorized"),

    /** Test outcome: the service is rate limiting the account. */
    @SerialName("service.rate_limited")
    RATE_LIMITED("service.rate_limited"),

    /** Test outcome: the service could not be reached. */
    @SerialName("service.unreachable")
    UNREACHABLE("service.unreachable"),

    /** Test outcome: the service did not answer in time. */
    @SerialName("service.upstream_timeout")
    UPSTREAM_TIMEOUT("service.upstream_timeout"),

    /** Test outcome: the service failed or answered with something unexpected. */
    @SerialName("service.upstream_error")
    UPSTREAM_ERROR("service.upstream_error"),

    /** This device's pairing lacks `admin.full` (the transport's own refusal). */
    @SerialName("forbidden")
    NOT_AUTHORIZED("forbidden"),

    /** The host does not serve this method: it predates the contract. */
    @SerialName("unsupported")
    UNSUPPORTED("unsupported"),

    /** Anything else, including the host's own internal errors. */
    @SerialName("internal")
    INTERNAL("internal"),
    ;

    /** Reading a failed response's `error`. */
    companion object {
        /** The code for [error]: an exact wire code, an unknown-method error, else [INTERNAL]. Never echoes [error]. */
        fun fromWire(error: String?): ServiceCredentialErrorCode = entries.firstOrNull { it.wire == error }
            ?: if (AdminRpcErrors.isUnknownMethod(error)) UNSUPPORTED else INTERNAL
    }
}

/** A service-credentials request failed with [code]. The message names the code only. */
class ServiceCredentialException(
    /** Why the request failed. */
    val code: ServiceCredentialErrorCode,
) : Exception("Service credential request failed: ${code.wire}")
