package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.audio.AudioChunk
import com.letta.mobile.data.voice.audio.AudioClip
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * An OpenAI-compatible speech client: `POST {base}/v1/audio/transcriptions` and
 * `POST {base}/v1/audio/speech`.
 *
 * A configured bearer key is sent only over https, or over plain http to a loopback host
 * (`localhost`, `127.0.0.0/8`, `::1`). Plain http to any other host, private LAN addresses
 * included, is refused with [VoiceErrorCode.INSECURE_TRANSPORT] before anything is sent: a LAN is
 * not an encrypted transport. Use https, or reach the server through a loopback tunnel.
 *
 * It builds its own isolated client on the supplied [engine] (no logging, no redirects, no shared
 * auth), sends the bearer key only when one is configured, and maps every failure to a
 * [SpeechEndpointException] with a fixed code. Nothing it records or throws contains the URL, the
 * key, the text or the audio.
 */
class SpeechEndpointClient(
    engine: HttpClientEngine,
    private val config: SpeechEndpointConfig,
    private val diagnostics: SpeechClientDiagnostics? = null,
) {
    private val client = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = config.timeouts.connect.inWholeMilliseconds
            requestTimeoutMillis = config.timeouts.request.inWholeMilliseconds
        }
    }

    /** The server this client talks to, for destination grant checks. */
    val endpoint: SpeechEndpointUrl get() = config.baseUrl

    /** Transcribes [request]'s audio and returns the text. */
    suspend fun transcribe(request: TranscriptionRequest): VoiceText = call(SpeechOperation.TRANSCRIPTION) {
        requireProtectedTransport(SpeechOperation.TRANSCRIPTION)
        val response = client.post(url(TRANSCRIPTIONS_PATH)) {
            authorize()
            setBody(transcriptionBody(request))
        }
        check(SpeechOperation.TRANSCRIPTION, response)
        parseTranscript(response.bodyAsText())
    }

    /** Synthesises [request], handing the audio to [onChunk] as it streams in. */
    suspend fun synthesize(request: SpeechRequest, onChunk: suspend (AudioChunk) -> Unit) {
        call(SpeechOperation.SPEECH) {
            requireProtectedTransport(SpeechOperation.SPEECH)
            client.preparePost(url(SPEECH_PATH)) {
                authorize()
                setBody(TextContent(speechBody(request), ContentType.Application.Json))
            }.execute { response ->
                check(SpeechOperation.SPEECH, response)
                stream(response, onChunk)
            }
        }
    }

    /** Synthesises [request] into one clip. */
    suspend fun synthesizeClip(request: SpeechRequest): AudioClip {
        val chunks = mutableListOf<ByteArray>()
        synthesize(request) { chunks += it.bytes }
        val bytes = ByteArray(chunks.sumOf { it.size })
        chunks.fold(0) { offset, chunk -> chunk.copyInto(bytes, offset).let { offset + chunk.size } }
        return AudioClip(bytes, request.encoding)
    }

    /** Cancels the requests in flight and closes the client. */
    fun close() {
        client.coroutineContext.cancel()
        client.close()
    }

    /** Refuses to send the bearer key unless the transport protects it. */
    private fun requireProtectedTransport(operation: SpeechOperation) {
        if (config.apiKey == null) return
        if (!SpeechTransportPolicy.protectsKey(config.baseUrl)) {
            throw SpeechEndpointException(operation, VoiceErrorCode.INSECURE_TRANSPORT)
        }
    }

    private fun url(path: String): String = config.baseUrl.value.trimEnd('/') + path

    private fun HttpRequestBuilder.authorize() {
        config.apiKey?.let { header(HttpHeaders.Authorization, it.bearer()) }
    }

    private fun transcriptionBody(request: TranscriptionRequest): MultiPartFormDataContent {
        val encoding = request.audio.encoding
        val fileHeaders = Headers.build {
            append(HttpHeaders.ContentType, encoding.mimeType)
            append(HttpHeaders.ContentDisposition, "filename=\"audio.${encoding.wireName}\"")
        }
        return MultiPartFormDataContent(
            formData {
                append("model", request.model.value)
                append("response_format", "json")
                request.language?.let { append("language", it.value) }
                append("file", request.audio.bytes, fileHeaders)
            },
        )
    }

    private fun speechBody(request: SpeechRequest): String = buildJsonObject {
        put("model", request.model.value)
        put("voice", request.voice.value)
        put("input", request.text.value)
        put("response_format", request.encoding.wireName)
    }.toString()

    private suspend fun stream(response: HttpResponse, onChunk: suspend (AudioChunk) -> Unit) {
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(CHUNK_BYTES)
        var received = 0L
        while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read > 0) onChunk(AudioChunk(buffer.copyOf(read)))
            received += read.coerceAtLeast(0)
        }
        if (received == 0L) throw SpeechEndpointException(SpeechOperation.SPEECH, VoiceErrorCode.BAD_RESPONSE, response.status.value)
    }

    private fun parseTranscript(body: String): VoiceText {
        val text = runCatching { Json.parseToJsonElement(body).jsonObject["text"] as? JsonPrimitive }.getOrNull()
        if (text == null || !text.isString) {
            throw SpeechEndpointException(SpeechOperation.TRANSCRIPTION, VoiceErrorCode.BAD_RESPONSE, HTTP_OK)
        }
        return VoiceText(text.content.trim())
    }

    private fun check(operation: SpeechOperation, response: HttpResponse) {
        val code = SpeechStatusCodes.failureFor(response.status.value) ?: return
        throw SpeechEndpointException(operation, code, response.status.value)
    }

    private suspend fun <T> call(operation: SpeechOperation, block: suspend () -> T): T {
        val outcome = runCatching { block() }
        val error = outcome.exceptionOrNull()
        if (error is CancellationException) throw error
        val failure = error?.let { classify(operation, it) }
        if (failure != null) {
            diagnostics?.record(SpeechClientEvent(operation, failure.code, failure.status))
            throw failure
        }
        diagnostics?.record(SpeechClientEvent(operation, null, HTTP_OK))
        return outcome.getOrThrow()
    }

    private fun classify(operation: SpeechOperation, error: Throwable): SpeechEndpointException = when (error) {
        is SpeechEndpointException -> error
        is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException ->
            SpeechEndpointException(operation, VoiceErrorCode.TIMED_OUT)
        else -> SpeechEndpointException(operation, VoiceErrorCode.UNREACHABLE)
    }

    private companion object {
        const val TRANSCRIPTIONS_PATH = "/v1/audio/transcriptions"
        const val SPEECH_PATH = "/v1/audio/speech"
        const val CHUNK_BYTES = 8 * 1024
        const val HTTP_OK = 200
    }
}

/** HTTP status to fixed failure code. */
internal object SpeechStatusCodes {
    private const val OK_FIRST = 200
    private const val OK_LAST = 299
    private val unauthorized = setOf(401, 403)
    private val timedOut = setOf(408, 504)

    /** The failure for [status], or null for success. */
    fun failureFor(status: Int): VoiceErrorCode? = when (status) {
        in OK_FIRST..OK_LAST -> null
        in unauthorized -> VoiceErrorCode.UNAUTHORIZED
        in timedOut -> VoiceErrorCode.TIMED_OUT
        else -> VoiceErrorCode.REJECTED
    }
}

/** Which endpoints may receive a bearer key. */
internal object SpeechTransportPolicy {
    private val loopbackNames = setOf("localhost", "::1", "[::1]")
    private const val LOOPBACK_V4_PREFIX = "127."

    /** True for https, and for plain http to a loopback host. */
    fun protectsKey(url: SpeechEndpointUrl): Boolean {
        val parsed = runCatching { Url(url.value) }.getOrNull() ?: return false
        return when (parsed.protocol) {
            URLProtocol.HTTPS -> true
            URLProtocol.HTTP -> isLoopback(parsed.host.lowercase())
            else -> false
        }
    }

    private fun isLoopback(host: String): Boolean = host in loopbackNames || host.startsWith(LOOPBACK_V4_PREFIX)
}
