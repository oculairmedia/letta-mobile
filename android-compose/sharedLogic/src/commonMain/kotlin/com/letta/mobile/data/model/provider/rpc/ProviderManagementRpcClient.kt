package com.letta.mobile.data.model.provider.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Immutable result of negotiating one exact provider-management contract version. */
data class ProviderRpcAvailability(
    val contractVersion: Int,
    val methods: Set<String>,
    val capabilities: Set<ProviderManagementCapability>,
) {
    companion object {
        fun negotiate(
            requestedVersion: Int,
            remoteVersions: Set<Int>,
            remoteMethods: Set<String>,
            remoteCapabilities: Set<ProviderManagementCapability>,
        ): ProviderRpcAvailability? {
            if (requestedVersion != ProviderRpcMethods.CONTRACT_VERSION || requestedVersion !in remoteVersions) {
                return null
            }
            return ProviderRpcAvailability(
                contractVersion = requestedVersion,
                methods = remoteMethods intersect ProviderRpcMethods.ALL_METHODS,
                capabilities = remoteCapabilities,
            )
        }
    }
}

/** Secret-safe transport request. Payload access is restricted to transport implementations. */
class ProviderRpcTransportRequest internal constructor(
    val contractName: String,
    val contractVersion: Int,
    val method: String,
    private val encodedPayload: String,
) {
    internal fun payloadForTransport(): String = encodedPayload

    override fun toString(): String =
        "ProviderRpcTransportRequest(contractName=$contractName, contractVersion=$contractVersion, " +
            "method=$method, payload=<redacted>)"
}

/** Secret-safe transport response. Body access is restricted to the RPC decoder. */
class ProviderRpcTransportResponse(private val encodedBody: String) {
    internal fun bodyForDecoding(): String = encodedBody

    override fun toString(): String = "ProviderRpcTransportResponse(body=<redacted>)"
}

fun interface ProviderRpcTransport {
    suspend fun execute(request: ProviderRpcTransportRequest): ProviderRpcTransportResponse
}

sealed interface ProviderRpcClientError {
    val code: String
    val method: String

    data object UnknownMethod : ProviderRpcClientError {
        override val method: String = "<unknown>"
        override val code: String = "UNKNOWN_METHOD"
    }

    data class UnsupportedContractVersion(
        override val method: String,
        val requestedVersion: Int,
    ) : ProviderRpcClientError {
        override val code: String = "UNSUPPORTED_CONTRACT_VERSION"
    }

    data class CapabilityUnavailable(override val method: String) : ProviderRpcClientError {
        override val code: String = "CAPABILITY_UNAVAILABLE"
    }

    data class CapabilityDenied(
        override val method: String,
        val required: ProviderManagementCapability,
    ) : ProviderRpcClientError {
        override val code: String = "CAPABILITY_DENIED"
    }

    data class ValidationFailed(override val method: String) : ProviderRpcClientError {
        override val code: String = "VALIDATION_FAILED"
    }

    data class MalformedResponse(override val method: String) : ProviderRpcClientError {
        override val code: String = "MALFORMED_RESPONSE"
    }

    data class TransportFailure(override val method: String) : ProviderRpcClientError {
        override val code: String = "TRANSPORT_FAILURE"
    }
}

sealed interface ProviderRpcClientResult<out Response> {
    data class Success<Response>(val value: Response) : ProviderRpcClientResult<Response>
    data class Failure(val error: ProviderRpcClientError) : ProviderRpcClientResult<Nothing>
}

private sealed interface RpcStep<out Result> {
    data class Value<Result>(val value: Result) : RpcStep<Result>
    data class Failed(val error: ProviderRpcClientError) : RpcStep<Nothing>
}

private fun <Result> RpcStep<Result>.toClientResult(): ProviderRpcClientResult<Result> = when (this) {
    is RpcStep.Value -> ProviderRpcClientResult.Success(value)
    is RpcStep.Failed -> ProviderRpcClientResult.Failure(error)
}

class ProviderRpcCodec<Request, Response>(
    internal val requestSerializer: KSerializer<Request>,
    internal val responseSerializer: KSerializer<Response>,
    internal val validateRequest: (Request) -> Boolean,
    internal val responseVersion: (Response) -> Int,
)

/**
 * One typed invocation. Request values are deliberately omitted from [toString] so credential
 * mutation DTOs cannot leak through diagnostics.
 */
class ProviderRpcCall<Request, Response>(
    val method: String,
    internal val request: Request,
    internal val codec: ProviderRpcCodec<Request, Response>,
    val requestedVersion: Int = ProviderRpcMethods.CONTRACT_VERSION,
) {
    override fun toString(): String =
        "ProviderRpcCall(method=$method, requestedVersion=$requestedVersion, request=<redacted>)"
}

/** Stateless KMP RPC boundary; availability is supplied per call so reconnect transitions are atomic. */
class ProviderManagementRpcClient(
    private val transport: ProviderRpcTransport,
    private val json: Json = Json,
) {
    suspend fun <Request, Response> execute(
        availability: ProviderRpcAvailability,
        call: ProviderRpcCall<Request, Response>,
    ): ProviderRpcClientResult<Response> {
        preflight(availability, call)?.let { return ProviderRpcClientResult.Failure(it) }
        val payload = when (val encoded = encode(call)) {
            is RpcStep.Value -> encoded.value
            is RpcStep.Failed -> return ProviderRpcClientResult.Failure(encoded.error)
        }
        val response = when (val sent = send(call, payload)) {
            is RpcStep.Value -> sent.value
            is RpcStep.Failed -> return ProviderRpcClientResult.Failure(sent.error)
        }
        return decode(call, response).toClientResult()
    }

    private fun <Request, Response> encode(call: ProviderRpcCall<Request, Response>): RpcStep<String> =
        try {
            RpcStep.Value(json.encodeToString(call.codec.requestSerializer, call.request))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            RpcStep.Failed(ProviderRpcClientError.ValidationFailed(call.method))
        }

    private suspend fun <Request, Response> send(
        call: ProviderRpcCall<Request, Response>,
        payload: String,
    ): RpcStep<ProviderRpcTransportResponse> = try {
        RpcStep.Value(
            transport.execute(
                ProviderRpcTransportRequest(
                    contractName = ProviderRpcMethods.CONTRACT_NAME,
                    contractVersion = call.requestedVersion,
                    method = call.method,
                    encodedPayload = payload,
                ),
            ),
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        RpcStep.Failed(ProviderRpcClientError.TransportFailure(call.method))
    }

    private fun <Request, Response> decode(
        call: ProviderRpcCall<Request, Response>,
        response: ProviderRpcTransportResponse,
    ): RpcStep<Response> = try {
        val decoded = json.decodeFromString(call.codec.responseSerializer, response.bodyForDecoding())
        val responseVersion = call.codec.responseVersion(decoded)
        if (responseVersion == call.requestedVersion) {
            RpcStep.Value(decoded)
        } else {
            RpcStep.Failed(ProviderRpcClientError.UnsupportedContractVersion(call.method, responseVersion))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        RpcStep.Failed(ProviderRpcClientError.MalformedResponse(call.method))
    }

    private fun <Request, Response> preflight(
        availability: ProviderRpcAvailability,
        call: ProviderRpcCall<Request, Response>,
    ): ProviderRpcClientError? {
        if (call.method !in ProviderRpcMethods.ALL_METHODS) {
            return ProviderRpcClientError.UnknownMethod
        }
        if (
            call.requestedVersion != ProviderRpcMethods.CONTRACT_VERSION ||
            availability.contractVersion != call.requestedVersion
        ) {
            return ProviderRpcClientError.UnsupportedContractVersion(call.method, call.requestedVersion)
        }
        if (call.method !in availability.methods) {
            return ProviderRpcClientError.CapabilityUnavailable(call.method)
        }
        val required = if (ProviderRpcMethods.isWriteMethod(call.method)) {
            ProviderManagementCapability.Write
        } else {
            ProviderManagementCapability.Read
        }
        if (required !in availability.capabilities) {
            return ProviderRpcClientError.CapabilityDenied(call.method, required)
        }
        if (!call.codec.validateRequest(call.request)) {
            return ProviderRpcClientError.ValidationFailed(call.method)
        }
        return null
    }
}
