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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
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
 * A configured bearer key is sent only over https, or over plain http to a host that is exactly a
 * loopback literal (`localhost`, a dotted-quad `127.x.x.x`, `::1`). Plain http to any other host,
 * private LAN addresses and look-alike names included, is refused with
 * [VoiceErrorCode.INSECURE_TRANSPORT] before anything is sent: a LAN is not an encrypted
 * transport. Use https, or reach the server through a loopback tunnel. The base URL must be plain
 * (no query, fragment, user info or whitespace; INVALID_ENDPOINT otherwise). The request URL is built
 * once, and the parsed URL is both what is checked and where the request goes; redirects are
 * never followed.
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
        val target = requestUrl(TRANSCRIPTIONS_ROUTE, SpeechOperation.TRANSCRIPTION)
        val response = client.post {
            url(target)
            authorize()
            setBody(transcriptionBody(request))
        }
        check(SpeechOperation.TRANSCRIPTION, response)
        parseTranscript(response.bodyAsText())
    }

    /** Synthesises [request], handing the audio to [onChunk] as it streams in. */
    suspend fun synthesize(request: SpeechRequest, onChunk: suspend (AudioChunk) -> Unit) {
        call(SpeechOperation.SPEECH) {
            val target = requestUrl(SPEECH_ROUTE, SpeechOperation.SPEECH)
            client.preparePost {
                url(target)
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

    /**
     * Checks that the server answers at all, with `GET {base}/v1/models`; no audio or text is sent,
     * so no destination grant is needed. Any HTTP answer counts as reachable except 401/403, which
     * fail with UNAUTHORIZED; the base URL and key rules apply as for every request.
     */
    suspend fun checkReachable() {
        call(SpeechOperation.REACHABILITY) {
            val target = requestUrl(MODELS_ROUTE, SpeechOperation.REACHABILITY)
            val response = client.get {
                url(target)
                authorize()
            }
            if (response.status.value in UNAUTHORIZED_STATUSES) {
                throw SpeechEndpointException(SpeechOperation.REACHABILITY, VoiceErrorCode.UNAUTHORIZED, response.status.value)
            }
        }
    }

    /** Cancels the requests in flight and closes the client. */
    fun close() {
        client.coroutineContext.cancel()
        client.close()
    }

    /**
     * Parses the request URL once; the parsed URL is both what the transport check validates and
     * what the request is sent to. Refuses to send the bearer key unless the transport protects it.
     */
    private fun requestUrl(route: List<String>, operation: SpeechOperation): Url {
        val target = SpeechEndpointAddress.resolve(config.baseUrl, route)
            ?: throw SpeechEndpointException(operation, VoiceErrorCode.INVALID_ENDPOINT)
        if (config.apiKey != null && !SpeechTransportPolicy.protectsKey(target)) {
            throw SpeechEndpointException(operation, VoiceErrorCode.INSECURE_TRANSPORT)
        }
        return target
    }

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
        val TRANSCRIPTIONS_ROUTE = listOf("v1", "audio", "transcriptions")
        val SPEECH_ROUTE = listOf("v1", "audio", "speech")
        val MODELS_ROUTE = listOf("v1", "models")
        val UNAUTHORIZED_STATUSES = setOf(401, 403)
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

/**
 * Builds request URLs from the parsed components of a base URL. The base must be a plain URL:
 * scheme, host, optional port and optional path prefix. A base with a query, a fragment, user info
 * or any whitespace is refused (null), rather than having a request path land in its query.
 */
internal object SpeechEndpointAddress {
    /** The request URL for [route] under [base], or null when [base] is not a plain base URL. */
    fun resolve(base: SpeechEndpointUrl, route: List<String>): Url? {
        if (base.value.any(Char::isWhitespace)) return null
        val parsed = runCatching { Url(base.value) }.getOrNull() ?: return null
        if (!isPlain(parsed)) return null
        return URLBuilder(parsed).apply { pathSegments = parsed.segments + route }.build()
    }

    private fun isPlain(url: Url): Boolean = when {
        url.encodedQuery.isNotEmpty() -> false
        url.encodedFragment.isNotEmpty() -> false
        else -> url.user == null && url.password == null
    }
}

/** Which endpoints may receive a bearer key. */
internal object SpeechTransportPolicy {
    /** True for https, and for plain http to a host that is exactly a loopback literal. */
    fun protectsKey(url: Url): Boolean {
        return when (url.protocol) {
            URLProtocol.HTTPS -> true
            URLProtocol.HTTP -> LoopbackHost.matches(url.host)
            else -> false
        }
    }
}

/**
 * A host that certainly means this machine: `localhost` (any case, no trailing dot), a dotted-quad
 * IPv4 literal of four decimal octets 0-255 starting with 127, or the IPv6 loopback `::1` in its
 * bracketed or full form. Look-alike names (`127.attacker.example`, `localhost.example.com`) and
 * short, octal, hexadecimal, integer or IPv4-mapped forms are refused rather than guessed at.
 */
internal object LoopbackHost {
    private const val LOCALHOST = "localhost"
    private const val LOOPBACK_FIRST_OCTET = 127
    private const val OCTETS = 4
    private const val MAX_OCTET = 255
    private const val MAX_OCTET_DIGITS = 3
    private val ipv6Loopback = setOf("::1", "[::1]", "0:0:0:0:0:0:0:1", "[0:0:0:0:0:0:0:1]")

    /** True when [host] is exactly a loopback name or literal. */
    fun matches(host: String): Boolean = when {
        host.lowercase() == LOCALHOST -> true
        host in ipv6Loopback -> true
        else -> isLoopbackIpv4(host)
    }

    private fun isLoopbackIpv4(host: String): Boolean {
        val octets = host.split('.').map(::octet)
        if (octets.size != OCTETS || null in octets) return false
        return octets.first() == LOOPBACK_FIRST_OCTET
    }

    /** One decimal octet without leading zeros (so octal forms are refused), or null. */
    private fun octet(text: String): Int? = when {
        text.isEmpty() || text.length > MAX_OCTET_DIGITS -> null
        !text.all { it in '0'..'9' } -> null
        text.length > 1 && text.first() == '0' -> null
        else -> text.toInt().takeIf { it <= MAX_OCTET }
    }
}
