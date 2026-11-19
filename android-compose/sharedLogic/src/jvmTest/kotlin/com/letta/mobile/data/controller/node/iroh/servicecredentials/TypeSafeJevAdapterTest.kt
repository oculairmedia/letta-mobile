package com.letta.mobile.data.controller.node.iroh.servicecredentials

import com.letta.mobile.data.servicecredentials.ServiceCredentialErrorCode
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class TypeSafeJevAdapterTest {
    /** The adapter's HttpTimeout counts real time; under runTest's virtual clock it would fire at once. */
    private fun realTime(block: suspend () -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }

    private val target = ServiceTarget("https://api.typesafe.example.invalid", "placeholder-key-not-real", mapOf("model" to "jev-test"))
    private val answered = """{"model":"jev-test","answers":{"connection_check":{"type":"score","score":1.0}}}"""

    private fun adapter(status: HttpStatusCode, body: String = "{}", sent: MutableList<String>? = null) = TypeSafeJevAdapter(
        MockEngine { request ->
            assertEquals("/v1/systemone", request.url.encodedPath)
            assertEquals("Bearer placeholder-key-not-real", request.headers[HttpHeaders.Authorization])
            sent?.add(String(request.body.toByteArray()))
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    @Test
    fun theTestSendsOnlyTheFixedSyntheticSentence() = realTime {
        val sent = mutableListOf<String>()
        assertNull(adapter(HttpStatusCode.OK, answered, sent).test(target))
        val body = Json.parseToJsonElement(sent.single()).jsonObject
        assertEquals(TypeSafeJevAdapter.probeBody("jev-test"), body)
    }

    @Test
    fun statusesMapToTestOutcomes() = realTime {
        assertEquals(ServiceCredentialErrorCode.UNAUTHORIZED, adapter(HttpStatusCode.Unauthorized).test(target))
        assertEquals(ServiceCredentialErrorCode.UNAUTHORIZED, adapter(HttpStatusCode.Forbidden).test(target))
        assertEquals(ServiceCredentialErrorCode.RATE_LIMITED, adapter(HttpStatusCode.TooManyRequests).test(target))
        assertEquals(ServiceCredentialErrorCode.UPSTREAM_ERROR, adapter(HttpStatusCode.fromValue(529)).test(target))
        assertEquals(ServiceCredentialErrorCode.UPSTREAM_ERROR, adapter(HttpStatusCode.OK, """{"answers":{}}""").test(target))
        assertEquals(ServiceCredentialErrorCode.UPSTREAM_ERROR, adapter(HttpStatusCode.OK, "not json").test(target))
    }

    @Test
    fun aConnectionFailureIsUnreachable() = realTime {
        val down = TypeSafeJevAdapter(MockEngine { throw IOException("connection refused") })
        assertEquals(ServiceCredentialErrorCode.UNREACHABLE, down.test(target))
    }
}
