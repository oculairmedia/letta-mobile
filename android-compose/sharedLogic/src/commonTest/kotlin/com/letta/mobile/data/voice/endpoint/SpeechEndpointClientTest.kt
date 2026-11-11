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
    private val base = SpeechEndpointUrl("http://speech.local:8880/")
    private val recorded = mutableListOf<HttpRequestData>()
    private val events = mutableListOf<SpeechClientEvent>()
    private val clip = AudioClip(byteArrayOf(1, 2, 3), AudioEncoding.WAV)
    private val transcription = TranscriptionRequest(clip, SpeechModelName("whisper-1"), SpeechLanguage("en"))
    private val speech = SpeechRequest(VoiceText("Hello there."), SpeechModelName("qwen3-tts"), SpeechVoiceName("clone"))

    private fun client(
        key: SpeechApiKey? = SpeechApiKey(secret),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): SpeechEndpointClient {
        val engine = MockEngine { request ->
            recorded += request
            handler(request)
        }
        return SpeechEndpointClient(engine, SpeechEndpointConfig(base, key)) { events += it }
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
}
