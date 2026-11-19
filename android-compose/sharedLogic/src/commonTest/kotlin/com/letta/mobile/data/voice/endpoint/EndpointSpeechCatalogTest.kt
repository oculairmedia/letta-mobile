package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.catalog.SpeechCatalogException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Listing a speech server directly: models, the voices extension and its absence, and the transport rules. */
class EndpointSpeechCatalogTest {
    private val secret = "sk-catalog-placeholder"
    private val recorded = mutableListOf<HttpRequestData>()

    private fun engine(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) = MockEngine { request ->
        recorded += request
        handler(request)
    }

    private fun catalog(
        base: String = "https://speech.example.invalid",
        key: String? = secret,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ) = EndpointSpeechCatalog(engine(handler), SpeechEndpointConfig(SpeechEndpointUrl(base), key?.let(::SpeechApiKey)))

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun realTimeTest(block: suspend CoroutineScope.() -> Unit): TestResult =
        runTest { withContext(Dispatchers.Default) { block() } }

    @Test
    fun listsModelsAndVoicesWithTheKey() = realTimeTest {
        val listed = catalog { request ->
            when (request.url.encodedPath) {
                "/v1/models" -> json("""{"data":[{"id":"whisper-1"},{"id":"tts-1"}]}""")
                else -> json("""{"voices":[{"id":"voice-a"},{"id":"voice-b"}]}""")
            }
        }.load()
        assertEquals(listOf("whisper-1", "tts-1"), listed.models)
        assertEquals(listOf("voice-a", "voice-b"), listed.voices)
        assertEquals(listOf("/v1/models", "/v1/audio/voices"), recorded.map { it.url.encodedPath })
        assertTrue(recorded.all { it.headers[HttpHeaders.Authorization] == "Bearer $secret" })
    }

    @Test
    fun aMissingOrFailingVoiceListingLeavesVoicesUnlisted() = realTimeTest {
        listOf(HttpStatusCode.NotFound, HttpStatusCode.InternalServerError).forEach { status ->
            val listed = catalog { request ->
                if (request.url.encodedPath == "/v1/models") json("""{"data":[{"id":"tts-1"}]}""") else json("""{"detail":"x"}""", status)
            }.load()
            assertEquals(listOf("tts-1"), listed.models)
            assertNull(listed.voices, status.toString())
        }
        val unreadable = catalog { request ->
            if (request.url.encodedPath == "/v1/models") json("""["tts-1"]""") else json("<html></html>")
        }.load()
        assertNull(unreadable.voices)
    }

    @Test
    fun aFailingModelListingFailsWithItsFixedCode() = realTimeTest {
        val cases = mapOf(
            HttpStatusCode.Unauthorized to VoiceErrorCode.UNAUTHORIZED,
            HttpStatusCode.NotFound to VoiceErrorCode.REJECTED,
            HttpStatusCode.GatewayTimeout to VoiceErrorCode.TIMED_OUT,
        )
        cases.forEach { (status, code) ->
            val failure = assertFailsWith<SpeechCatalogException> { catalog { json("""{"error":"x"}""", status) }.load() }
            assertEquals(code, failure.code)
        }
        val bad = assertFailsWith<SpeechCatalogException> { catalog { json("""{"detail":"x"}""") }.load() }
        assertEquals(VoiceErrorCode.BAD_RESPONSE, bad.code)
        val down = assertFailsWith<SpeechCatalogException> { catalog { error("connection refused") }.load() }
        assertEquals(VoiceErrorCode.UNREACHABLE, down.code)
    }

    @Test
    fun aKeyIsNeverSentOverPlainHttpToAnotherHost() = realTimeTest {
        val failure = assertFailsWith<SpeechCatalogException> {
            catalog(base = "http://speech.example.invalid") { json("""["tts-1"]""") }.load()
        }
        assertEquals(VoiceErrorCode.INSECURE_TRANSPORT, failure.code)
        assertTrue(recorded.isEmpty())
        val keyless = catalog(base = "http://speech.example.invalid", key = null) { json("""["tts-1"]""") }.load()
        assertEquals(listOf("tts-1"), keyless.models)
        assertFalse(recorded.any { it.headers.contains(HttpHeaders.Authorization) })
    }

    @Test
    fun failuresNeverCarryTheAddressOrTheKey() = realTimeTest {
        val failure = assertFailsWith<SpeechCatalogException> { catalog { json("""{"error":"x"}""", HttpStatusCode.Forbidden) }.load() }
        val text = failure.toString() + failure.message
        assertFalse("speech.example.invalid" in text)
        assertFalse(secret in text)
    }

    @Test
    fun theOneShotListingTreatsABlankKeyAsNone() = realTimeTest {
        val listed = EndpointSpeechCatalog.list("http://127.0.0.1:8880", " ", engine { json("""{"data":[{"id":"tts-1"}]}""") })
        assertEquals(listOf("tts-1"), listed.models)
        assertFalse(recorded.any { it.headers.contains(HttpHeaders.Authorization) })
    }
}
