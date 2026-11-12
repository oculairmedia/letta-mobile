package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.SpeechLanguage
import com.letta.mobile.data.voice.SpeechModelName
import com.letta.mobile.data.voice.SpeechVoiceName
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
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

class SpeechEndpointClientTest {
    private val secret = "sk-voice-secret-4711"
    private val base = SpeechEndpointUrl("https://speech.local:8880/")
    private val recorded = mutableListOf<HttpRequestData>()
    private val events = mutableListOf<SpeechClientEvent>()
    private val clip = AudioClip(byteArrayOf(1, 2, 3), AudioEncoding.WAV)
    private val transcription = TranscriptionRequest(clip, SpeechModelName("whisper-1"), SpeechLanguage("en"))
    private val speech = SpeechRequest(VoiceText("Hello there."), SpeechModelName("qwen3-tts"), SpeechVoiceName("clone"))

    private fun client(
        key: SpeechApiKey? = SpeechApiKey(secret),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SpeechEndpointClient = client(SpeechEndpointConfig(base, key), handler)

    private fun client(
        config: SpeechEndpointConfig,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SpeechEndpointClient {
        val engine = MockEngine { request ->
            recorded += request
            handler(request)
        }
        return SpeechEndpointClient(engine, config) { events += it }
    }

    /** HttpTimeout delays on the test scheduler would fire at once; run requests on real time. */
    private fun realTimeTest(block: suspend CoroutineScope.() -> Unit): TestResult =
        runTest { withContext(Dispatchers.Default) { block() } }

    private fun MockRequestHandleScope.json(body: String) =
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun transcriptionSendsMultipartWithModelLanguageAndFile() = realTimeTest {
        val text = client { json("""{"text":"  what time is it "}""") }.transcribe(transcription)
        assertEquals("what time is it", text.value)
        val request = recorded.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/v1/audio/transcriptions", request.url.encodedPath)
        val body = request.body.toByteArray().decodeToString().replace("\"", "")
        listOf("name=model", "whisper-1", "name=response_format", "name=language", "filename=audio.wav", "audio/wav")
            .forEach { assertTrue(it in body, it) }
        assertEquals("Bearer $secret", request.headers[HttpHeaders.Authorization])
    }

    @Test
    fun noKeyMeansNoAuthorizationHeader() = realTimeTest {
        client(key = null) { json("""{"text":"hi"}""") }.transcribe(transcription)
        assertNull(recorded.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun speechSendsJsonAndStreamsAudio() = realTimeTest {
        val audio = ByteArray(20_000) { it.toByte() }
        val received = client { respond(audio, HttpStatusCode.OK) }.synthesizeClip(speech)
        assertTrue(audio.contentEquals(received.bytes))
        val request = recorded.single()
        assertEquals("/v1/audio/speech", request.url.encodedPath)
        val body = request.body.toByteArray().decodeToString()
        listOf("\"model\":\"qwen3-tts\"", "\"voice\":\"clone\"", "\"input\":\"Hello there.\"", "\"response_format\":\"mp3\"")
            .forEach { assertTrue(it in body, it) }
    }

    @Test
    fun statusCodesMapToFixedFailures() = realTimeTest {
        val expected = mapOf(
            HttpStatusCode.Unauthorized to VoiceErrorCode.UNAUTHORIZED,
            HttpStatusCode.Forbidden to VoiceErrorCode.UNAUTHORIZED,
            HttpStatusCode.BadRequest to VoiceErrorCode.REJECTED,
            HttpStatusCode.InternalServerError to VoiceErrorCode.REJECTED,
            HttpStatusCode.GatewayTimeout to VoiceErrorCode.TIMED_OUT,
        )
        expected.forEach { (status, code) ->
            val failure = assertFailsWith<SpeechEndpointException> {
                client { respond("nope", status) }.transcribe(transcription)
            }
            assertEquals(code, failure.code)
            assertEquals(status.value, failure.status)
        }
    }

    @Test
    fun malformedOrEmptyBodiesAreBadResponses() = realTimeTest {
        val transcript = assertFailsWith<SpeechEndpointException> { client { json("""{"words":1}""") }.transcribe(transcription) }
        assertEquals(VoiceErrorCode.BAD_RESPONSE, transcript.code)
        val audio = assertFailsWith<SpeechEndpointException> { client { respond(ByteArray(0), HttpStatusCode.OK) }.synthesizeClip(speech) }
        assertEquals(VoiceErrorCode.BAD_RESPONSE, audio.code)
    }

    @Test
    fun transportFailuresMapToUnreachableAndTimedOut() = realTimeTest {
        val unreachable = assertFailsWith<SpeechEndpointException> {
            client { error("connection refused") }.transcribe(transcription)
        }
        assertEquals(VoiceErrorCode.UNREACHABLE, unreachable.code)
        val timedOut = assertFailsWith<SpeechEndpointException> {
            client { throw HttpRequestTimeoutException("hidden", 1L) }.synthesizeClip(speech)
        }
        assertEquals(VoiceErrorCode.TIMED_OUT, timedOut.code)
    }

    @Test
    fun keyAppearsInNoMessageEventOrToString() = realTimeTest {
        val failure = assertFailsWith<SpeechEndpointException> {
            client { respond("bad key $secret", HttpStatusCode.Unauthorized) }.transcribe(transcription)
        }
        client { json("""{"text":"ok"}""") }.transcribe(transcription)
        val surfaces = listOf(
            failure.message.orEmpty(),
            failure.toString(),
            SpeechApiKey(secret).toString(),
            SpeechEndpointConfig(base, SpeechApiKey(secret)).toString(),
        ) + events.map { it.toString() }
        surfaces.forEach { assertFalse(secret in it, it) }
        assertFalse(base.value.removeSuffix("/") in failure.message.orEmpty())
        assertEquals(listOf(VoiceErrorCode.UNAUTHORIZED, null), events.map { it.failure })
    }

    @Test
    fun bearerKeyTravelsOnlyOverHttpsOrLoopback() = realTimeTest {
        val cases: Map<String, VoiceErrorCode?> = mapOf(
            "https://speech.example.net" to null,
            "http://127.0.0.1:8880" to null,
            "http://127.255.255.254" to null,
            "http://localhost:8880" to null,
            "http://LOCALHOST:8880" to null,
            "http://[::1]:8880" to null,
            "http://[0:0:0:0:0:0:0:1]:8880" to null,
            "http://localhost.:8880" to VoiceErrorCode.INSECURE_TRANSPORT,
            "http://127.attacker.example" to VoiceErrorCode.INSECURE_TRANSPORT,
            "http://127.0.0.1.example.com" to VoiceErrorCode.INSECURE_TRANSPORT,
            "http://127.1" to VoiceErrorCode.INSECURE_TRANSPORT,
            "http://2130706433" to VoiceErrorCode.INSECURE_TRANSPORT,
            "http://0x7f.0.0.1" to VoiceErrorCode.INSECURE_TRANSPORT,
            "http://0177.0.0.1" to VoiceErrorCode.INSECURE_TRANSPORT,
            "http://127.0.0.256" to VoiceErrorCode.INSECURE_TRANSPORT,
            "http://localhost.example.com" to VoiceErrorCode.INSECURE_TRANSPORT,
            "http://localhost@evil.example/" to VoiceErrorCode.INVALID_ENDPOINT,
            "http://[::ffff:127.0.0.1]:8880" to VoiceErrorCode.INSECURE_TRANSPORT,
            " http://localhost:8880" to VoiceErrorCode.INVALID_ENDPOINT,
            "http://localhost:8880 " to VoiceErrorCode.INVALID_ENDPOINT,
            "http://192.168.1.20:8880" to VoiceErrorCode.INSECURE_TRANSPORT,
            "http://10.0.0.5" to VoiceErrorCode.INSECURE_TRANSPORT,
            "http://speech.example.net" to VoiceErrorCode.INSECURE_TRANSPORT,
        )
        cases.forEach { (url, refusal) ->
            recorded.clear()
            val config = SpeechEndpointConfig(SpeechEndpointUrl(url), SpeechApiKey(secret))
            val outcome = runCatching { client(config) { json("""{"text":"ok"}""") }.transcribe(transcription) }
            val failure = outcome.exceptionOrNull() as? SpeechEndpointException
            assertEquals(refusal, failure?.code, url)
            assertEquals(if (refusal == null) 1 else 0, recorded.size, url)
            if (refusal == null) assertEquals(Url(url).host, recorded.single().url.host, url)
            assertFalse(secret in failure?.message.orEmpty())
        }
    }

    @Test
    fun plainHttpWithoutAKeyIsAllowed() = realTimeTest {
        val config = SpeechEndpointConfig(SpeechEndpointUrl("http://192.168.1.20:8880"))
        assertEquals("ok", client(config) { json("""{"text":"ok"}""") }.transcribe(transcription).value)
    }

    @Test
    fun redirectsAreNeverFollowed() = realTimeTest {
        val config = SpeechEndpointConfig(SpeechEndpointUrl("http://127.0.0.1:8880"), SpeechApiKey(secret))
        val redirect = headersOf(HttpHeaders.Location, "http://evil.example/v1/audio/transcriptions")
        val failure = assertFailsWith<SpeechEndpointException> {
            client(config) { respond("", HttpStatusCode.Found, redirect) }.transcribe(transcription)
        }
        assertEquals(VoiceErrorCode.REJECTED, failure.code)
        assertEquals(listOf("127.0.0.1"), recorded.map { it.url.host })
    }

    @Test
    fun requestUrlsAreBuiltFromParsedComponents() = realTimeTest {
        val cases = mapOf(
            "https://speech.local" to "/v1/audio/transcriptions",
            "https://speech.local/" to "/v1/audio/transcriptions",
            "https://speech.local/prefix" to "/prefix/v1/audio/transcriptions",
            "https://speech.local/prefix/" to "/prefix/v1/audio/transcriptions",
            "https://speech.local/api?tenant=a" to null,
            "https://speech.local/api#part" to null,
            "https://user:pw@speech.local" to null,
        )
        cases.forEach { (url, path) ->
            recorded.clear()
            val config = SpeechEndpointConfig(SpeechEndpointUrl(url), SpeechApiKey(secret))
            val outcome = runCatching { client(config) { json("""{"text":"ok"}""") }.transcribe(transcription) }
            if (path == null) {
                assertEquals(VoiceErrorCode.INVALID_ENDPOINT, (outcome.exceptionOrNull() as? SpeechEndpointException)?.code, url)
                assertEquals(0, recorded.size, url)
            } else {
                assertEquals(path, recorded.single().url.encodedPath, url)
                assertEquals("", recorded.single().url.encodedQuery, url)
            }
        }
    }
}
