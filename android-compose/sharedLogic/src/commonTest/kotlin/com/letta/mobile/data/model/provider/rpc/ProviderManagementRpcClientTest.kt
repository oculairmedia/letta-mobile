package com.letta.mobile.data.model.provider.rpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class ProviderManagementRpcClientTest {

    @Test
    fun negotiationRequiresTheExactRequestedVersionWithoutDowngrade() {
        assertEquals(
            null,
            ProviderRpcAvailability.negotiate(
                requestedVersion = 2,
                remoteVersions = setOf(1, 2),
                remoteMethods = ProviderRpcMethods.ALL_METHODS,
                remoteCapabilities = setOf(ProviderManagementCapability.Read),
            ),
        )
        val negotiated = ProviderRpcAvailability.negotiate(
            requestedVersion = 1,
            remoteVersions = setOf(1, 2),
            remoteMethods = ProviderRpcMethods.ALL_METHODS,
            remoteCapabilities = setOf(ProviderManagementCapability.Read),
        )
        assertEquals(1, negotiated?.contractVersion)
    }

    @Test
    fun unavailableDeniedIncompatibleAndInvalidCallsNeverReachNetwork() = runTest {
        val transport = RecordingTransport(successResponse())
        val client = ProviderManagementRpcClient(transport)
        val call = call()

        assertIs<ProviderRpcClientError.CapabilityUnavailable>(
            failure(client.execute(availability(methods = emptySet()), call)),
        )
        assertIs<ProviderRpcClientError.CapabilityDenied>(
            failure(client.execute(availability(capabilities = emptySet()), call)),
        )
        assertIs<ProviderRpcClientError.UnsupportedContractVersion>(
            failure(client.execute(availability(version = 2), call)),
        )
        assertIs<ProviderRpcClientError.ValidationFailed>(
            failure(client.execute(availability(), call(request = TestRequest("")))),
        )
        assertEquals(0, transport.calls)
    }

    @Test
    fun malformedResponseAndTransportFailureAreTypedAndSecretSafe() = runTest {
        val malformed = RecordingTransport(ProviderRpcTransportResponse("{not-json:sk-secret}"))
        val malformedError = failure(ProviderManagementRpcClient(malformed).execute(availability(), call()))
        assertIs<ProviderRpcClientError.MalformedResponse>(malformedError)
        assertEquals(false, malformedError.toString().contains("sk-secret"))

        val failed = RecordingTransport(failure = IllegalStateException("secret transport detail"))
        val transportError = failure(ProviderManagementRpcClient(failed).execute(availability(), call()))
        assertIs<ProviderRpcClientError.TransportFailure>(transportError)
        assertEquals(false, transportError.toString().contains("secret transport detail"))
    }

    @Test
    fun cancellationPropagatesAndIsNeverConvertedToAnRpcError() = runTest {
        val transport = RecordingTransport(failure = CancellationException("cancel transport"))
        assertFailsWith<CancellationException> {
            ProviderManagementRpcClient(transport).execute(availability(), call())
        }
    }

    @Test
    fun capabilityTransitionsUsePerCallSnapshotsWithoutClientGlobalState() = runTest {
        val transport = RecordingTransport(successResponse())
        val client = ProviderManagementRpcClient(transport)

        assertIs<ProviderRpcClientError.CapabilityDenied>(
            failure(client.execute(availability(capabilities = emptySet()), call())),
        )
        assertIs<ProviderRpcClientResult.Success<TestResponse>>(
            client.execute(availability(capabilities = setOf(ProviderManagementCapability.Read)), call()),
        )
        assertIs<ProviderRpcClientError.CapabilityDenied>(
            failure(client.execute(availability(capabilities = emptySet()), call())),
        )
        assertEquals(1, transport.calls)
        assertEquals(ProviderRpcMethods.CONTRACT_NAME, transport.lastRequest?.contractName)
        assertEquals(ProviderRpcMethods.PROVIDER_INSTANCE_LIST, transport.lastRequest?.method)
    }

    @Test
    fun responseVersionMismatchDoesNotSilentlyDowngrade() = runTest {
        val response = ProviderRpcTransportResponse("""{"contract_version":2,"value":"ignored"}""")
        val error = failure(
            ProviderManagementRpcClient(RecordingTransport(response)).execute(availability(), call()),
        )
        val unsupported = assertIs<ProviderRpcClientError.UnsupportedContractVersion>(error)
        assertEquals(2, unsupported.requestedVersion)
    }

    private fun availability(
        version: Int = 1,
        methods: Set<String> = setOf(ProviderRpcMethods.PROVIDER_INSTANCE_LIST),
        capabilities: Set<ProviderManagementCapability> = setOf(ProviderManagementCapability.Read),
    ) = ProviderRpcAvailability(version, methods, capabilities)

    private fun call(request: TestRequest = TestRequest("host-1")) = ProviderRpcCall(
        method = ProviderRpcMethods.PROVIDER_INSTANCE_LIST,
        request = request,
        codec = ProviderRpcCodec(
            requestSerializer = TestRequest.serializer(),
            responseSerializer = TestResponse.serializer(),
            validateRequest = { it.hostId.isNotBlank() },
            responseVersion = { it.contractVersion },
        ),
    )

    private fun successResponse() =
        ProviderRpcTransportResponse("""{"contract_version":1,"value":"ok"}""")

    private fun failure(result: ProviderRpcClientResult<TestResponse>): ProviderRpcClientError =
        assertIs<ProviderRpcClientResult.Failure>(result).error

    @Serializable
    private data class TestRequest(@SerialName("host_id") val hostId: String)

    @Serializable
    private data class TestResponse(
        @SerialName("contract_version") val contractVersion: Int,
        val value: String,
    )

    private class RecordingTransport(
        private val response: ProviderRpcTransportResponse? = null,
        private val failure: Throwable? = null,
    ) : ProviderRpcTransport {
        var calls: Int = 0
        var lastRequest: ProviderRpcTransportRequest? = null

        override suspend fun execute(request: ProviderRpcTransportRequest): ProviderRpcTransportResponse {
            calls += 1
            lastRequest = request
            failure?.let { throw it }
            return requireNotNull(response)
        }
    }
}
