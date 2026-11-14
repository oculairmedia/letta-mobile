package com.letta.mobile.data.secrets.client

import com.letta.mobile.data.secrets.request.SecretReceiptStatus
import com.letta.mobile.data.secrets.request.SecretRequestList
import com.letta.mobile.data.secrets.request.SecretRequestMethods
import com.letta.mobile.data.secrets.request.SecretRequestProblem
import com.letta.mobile.data.secrets.request.SecretRequestRefusal
import com.letta.mobile.data.secrets.request.SecretRequestState
import com.letta.mobile.data.secrets.request.SecretRequestTestData
import com.letta.mobile.data.secrets.request.SecretServiceError
import com.letta.mobile.data.secrets.wire.SecretRequestRouteWire
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdminRpcSecretRequestClientTest {
    /** A host connection that answers every call with [answer] and remembers what was sent. */
    private class FakeLink(
        override val hostCapabilities: Set<String> = setOf(SecretRequestRouteWire.CAPABILITY),
        val answer: suspend (String) -> AppServerInboundFrame.AdminRpcResponse = { ok(RECEIPT) },
    ) : SecretRequestLink {
        val calls = mutableListOf<Pair<String, String>>()

        override suspend fun call(method: String, body: String): AppServerInboundFrame.AdminRpcResponse {
            calls += method to body
            return answer(method)
        }
    }

    private val id = SecretRequestTestData.id(1)

    private fun input(text: String): SecretInput = SecretInput.ofBytes(text.encodeToByteArray())

    @Test
    fun aSubmissionCarriesTheValueOnceAndMapsTheReceipt() = runTest {
        val link = FakeLink()
        val value = input("client-value")
        val outcome = AdminRpcSecretRequestClient(link).submit(id, value)
        assertEquals(SecretClientOutcome.Settled(FakeSecretRequestClient.receipt(SecretReceiptStatus.SAVED)), outcome)
        val (method, body) = link.calls.single()
        assertEquals(SecretRequestMethods.SUBMIT, method)
        val params = Json.parseToJsonElement(body).jsonObject
        assertEquals(setOf(SecretRequestRouteWire.REQUEST_ID, SecretRequestRouteWire.VALUE), params.keys)
        assertEquals("client-value", Base64.decode(params.getValue(SecretRequestRouteWire.VALUE).jsonPrimitive.content).decodeToString())
        assertTrue(value.isWiped)
    }

    @Test
    fun theHostsFixedCodesAreRefusalsAndAnythingElseIsUnreachable() = runTest {
        val cases = mapOf(
            "not_pending" to SecretClientOutcome.Refused(SecretRequestRefusal(SecretServiceError.NOT_PENDING)),
            "forbidden" to SecretClientOutcome.Refused(SecretRequestRefusal(SecretServiceError.FORBIDDEN)),
            "invalid_value" to SecretClientOutcome.Refused(SecretRequestRefusal(SecretServiceError.INVALID_VALUE)),
            "invalid_request:purpose_blank" to SecretClientOutcome.Refused(
                SecretRequestRefusal(SecretServiceError.INVALID_REQUEST, SecretRequestProblem.PURPOSE_BLANK),
            ),
            "Internal error" to SecretClientOutcome.Unreachable,
            "Unknown method: secret.request.submit" to SecretClientOutcome.Unreachable,
        )
        for ((error, expected) in cases) {
            val link = FakeLink { failed(error) }
            val value = input("refused-value")
            assertEquals(expected, AdminRpcSecretRequestClient(link).submit(id, value), error)
            assertTrue(value.isWiped, error)
        }
    }

    @Test
    fun aCallThatFailsOrComesBackUnreadableIsUnreachableAndTheValueIsWiped() = runTest {
        val links = listOf(
            FakeLink { error("connection lost") },
            FakeLink { ok(Json.parseToJsonElement("""{"receipt":{"status":"bogus"}}""")) },
            FakeLink { ok(Json.parseToJsonElement("{}")) },
        )
        for (link in links) {
            val value = input("unreachable-value")
            assertEquals(SecretClientOutcome.Unreachable, AdminRpcSecretRequestClient(link).submit(id, value))
            assertTrue(value.isWiped)
            assertEquals(1, link.calls.size, "never retried")
        }
    }

    @Test
    fun aCancelledSubmissionWipesTheValue() = runTest {
        val sent = CompletableDeferred<Unit>()
        val link = FakeLink {
            sent.complete(Unit)
            awaitCancellation()
        }
        val value = input("cancelled-value")
        val job = launch { AdminRpcSecretRequestClient(link).submit(id, value) }
        sent.await()
        job.cancel()
        job.join()
        assertTrue(value.isWiped)
    }

    @Test
    fun withoutTheCapabilityNothingIsSentAndNothingIsListed() = runTest {
        val link = FakeLink(hostCapabilities = emptySet())
        val client = AdminRpcSecretRequestClient(link)
        val value = input("unsupported-value")
        assertEquals(SecretClientOutcome.Unreachable, client.submit(id, value))
        assertTrue(value.isWiped)
        assertEquals(SecretClientOutcome.Unreachable, client.decline(id))
        assertEquals(emptyList(), client.pending(SecretRequestScope.Everyone).first())
        assertTrue(link.calls.isEmpty())
    }

    @Test
    fun declineSendsOnlyTheRequestId() = runTest {
        val link = FakeLink { ok(RECEIPT_CANCELLED) }
        val outcome = AdminRpcSecretRequestClient(link).decline(id)
        assertEquals(SecretClientOutcome.Settled(FakeSecretRequestClient.receipt(SecretReceiptStatus.CANCELLED)), outcome)
        assertEquals(SecretRequestMethods.CANCEL to """{"requestId":"${id.value}"}""", link.calls.single())
    }

    @Test
    fun pendingListsOnlyOpenRequestsInScope() = runTest {
        val open = SecretRequestTestData.record(SecretRequestTestData.id(1))
        val answered = SecretRequestTestData.record(SecretRequestTestData.id(2), SecretRequestTestData.status(SecretRequestState.SAVED))
        val other = open.copy(
            requestId = SecretRequestTestData.id(3),
            details = open.details.copy(requestingAgent = SecretRequestTestData.OTHER_AGENT),
        )
        val listing = Json.encodeToJsonElement(SecretRequestList.serializer(), SecretRequestList(listOf(other, answered, open)))
        val client = AdminRpcSecretRequestClient(FakeLink { ok(listing) })
        assertEquals(listOf(other, open), client.pending(SecretRequestScope.Everyone).first())
        assertEquals(listOf(open), client.pending(SecretRequestScope.Agent(SecretRequestTestData.AGENT)).first())
    }

    private companion object {
        val RECEIPT: JsonElement = Json.parseToJsonElement(
            """{"receipt":{"status":"saved","itemId":"team/openai","fieldName":"api_key"}}""",
        )
        val RECEIPT_CANCELLED: JsonElement = Json.parseToJsonElement(
            """{"receipt":{"status":"cancelled","itemId":"team/openai","fieldName":"api_key"}}""",
        )

        fun ok(result: JsonElement): AppServerInboundFrame.AdminRpcResponse =
            AppServerInboundFrame.AdminRpcResponse(requestId = "c", success = true, result = result)

        fun failed(error: String): AppServerInboundFrame.AdminRpcResponse =
            AppServerInboundFrame.AdminRpcResponse(requestId = "c", success = false, error = error)
    }
}
