package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.endpoint.SpeechEndpointAddress
import com.letta.mobile.data.voice.endpoint.SpeechTransportPolicy
import com.letta.mobile.data.voice.endpoint.TranscriptResponse
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
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
import io.ktor.http.Url
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * [VoiceSpeechUpstream] over HTTP on a Ktor [engine]: an isolated client with no logging, no redirects
 * and no shared auth. The base URL must be plain (the address rules of the device-side speech client),
 * and a key is sent only over https or plain http to a loopback literal, else the call is refused with
 * `voice.insecure_transport` before anything is sent. Every budget in [timeouts] ends the call with
 * `voice.upstream_timeout`. No response body is ever logged or passed on; only its parsed fields are.
 */
internal class KtorVoiceSpeechUpstream(
    engine: HttpClientEngine,
    private val timeouts: VoiceUpstreamTimeouts = VoiceUpstreamTimeouts(),
) : VoiceSpeechUpstream {
    private val client = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = timeouts.connect.inWholeMilliseconds
            socketTimeoutMillis = timeouts.firstByte.inWholeMilliseconds
        }
    }

    override suspend fun models(target: VoiceUpstreamTarget): List<String> = guarded(timeouts.reachability.inWholeMilliseconds) {
        val response = client.get { aimAt(target, MODELS_ROUTE) }
        requireSuccess(response)
        ids(parse(response.bodyAsText()), "data", "models") ?: throw VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE)
    }

    override suspend fun voices(target: VoiceUpstreamTarget): List<String>? = guarded(timeouts.reachability.inWholeMilliseconds) {
        val response = client.get { aimAt(target, VOICES_ROUTE) }
        if (statusFailure(response.status.value) != null) return@guarded null
        ids(parse(response.bodyAsText()), "voices", "data")?.takeIf { it.isNotEmpty() }
    }

    override suspend fun transcribe(target: VoiceUpstreamTarget, request: VoiceTranscriptionCall): VoiceTranscriptionAnswer =
        guarded(timeouts.transcription.inWholeMilliseconds) {
            val response = client.post {
                aimAt(target, TRANSCRIPTIONS_ROUTE)
                setBody(transcriptionBody(request))
            }
            if (request.verbose && response.status.value in FORMAT_REFUSED_STATUSES) return@guarded VoiceTranscriptionAnswer.FormatRefused
            requireSuccess(response)
            val transcript = TranscriptResponse.parse(response.bodyAsText())
                ?: throw VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE, response.status.value)
            VoiceTranscriptionAnswer.Heard(transcript)
        }

    override suspend fun speech(target: VoiceUpstreamTarget, request: VoiceSpeechCall, onBytes: suspend (ByteArray) -> Unit) {
        guarded(timeouts.synthesis.inWholeMilliseconds) {
            coroutineScope {
                val answered = CompletableDeferred<Unit>()
                val firstByte = launch {
                    if (withTimeoutOrNull(timeouts.firstByte.inWholeMilliseconds) { answered.await() } == null) {
                        throw VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_TIMEOUT)
                    }
                }
                client.preparePost {
                    aimAt(target, SPEECH_ROUTE)
                    setBody(TextContent(speechBody(request), ContentType.Application.Json))
                }.execute { response ->
                    answered.complete(Unit)
                    requireSuccess(response)
                    stream(response, onBytes)
                }
                firstByte.cancel()
            }
        }
    }

    override fun close() {
        client.coroutineContext.cancel()
        client.close()
    }

    /** Points the request at [route] under the target's base, refusing a key that would travel unencrypted. */
    private fun HttpRequestBuilder.aimAt(target: VoiceUpstreamTarget, route: List<String>) {
        val address = SpeechEndpointAddress.resolve(SpeechEndpointUrl(target.baseUrl), route)
            ?: throw VoiceUpstreamFailure(HostVoiceErrorCode.INVALID_REQUEST)
        val key = target.apiKey
        if (key != null && !SpeechTransportPolicy.protectsKey(address)) {
            throw VoiceUpstreamFailure(HostVoiceErrorCode.INSECURE_TRANSPORT)
        }
        url(address)
        key?.let { header(HttpHeaders.Authorization, "Bearer $it") }
    }

    /** Reads the body as it streams; a body that breaks off is a bad response, not an unreachable server. */
    private suspend fun stream(response: HttpResponse, onBytes: suspend (ByteArray) -> Unit) {
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(CHUNK_BYTES)
        while (true) {
            val read = readRun(channel, buffer, response.status.value)
            if (read < 0) return
            if (read > 0) onBytes(buffer.copyOf(read))
        }
    }

    /** One run of the body into [buffer]: its length, or -1 at the end. A stall is a timeout, a break a bad response. */
    private suspend fun readRun(channel: ByteReadChannel, buffer: ByteArray, status: Int): Int = try {
        channel.readAvailable(buffer, 0, buffer.size)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (timedOut: SocketTimeoutException) {
        throw VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_TIMEOUT, status)
    } catch (expectedBreak: Exception) {
        throw VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE, status)
    }

    private fun requireSuccess(response: HttpResponse) {
        statusFailure(response.status.value)?.let { throw VoiceUpstreamFailure(it, response.status.value) }
    }

    private fun transcriptionBody(request: VoiceTranscriptionCall): MultiPartFormDataContent {
        val fileHeaders = Headers.build {
            append(HttpHeaders.ContentType, WAV_MIME)
            append(HttpHeaders.ContentDisposition, "filename=\"audio.wav\"")
        }
        return MultiPartFormDataContent(
            formData {
                append("model", request.model)
                append("response_format", if (request.verbose) VERBOSE_JSON else PLAIN_JSON)
                request.language?.let { append("language", it) }
                append("file", request.audio, fileHeaders)
            },
        )
    }

    private fun speechBody(request: VoiceSpeechCall): String = buildJsonObject {
        put("model", request.model)
        put("voice", request.voice)
        put("input", request.text)
        put("response_format", WAV)
    }.toString()

    /**
     * Runs [block] within [totalMs], mapping every failure to the closed set ([classify]). Cancellation
     * passes through.
     */
    private suspend fun <T> guarded(totalMs: Long, block: suspend () -> T): T = try {
        withTimeout(totalMs) { block() }
    } catch (expectedFailure: Exception) {
        throw classify(expectedFailure)
    }

    /**
     * A timeout of any kind is `voice.upstream_timeout`; anything else that is not already a
     * [VoiceUpstreamFailure] or a cancellation means the server could not be reached.
     */
    private fun classify(error: Exception): Exception = when (error) {
        is TimeoutCancellationException -> VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_TIMEOUT)
        is VoiceUpstreamFailure, is CancellationException -> error
        is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException ->
            VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_TIMEOUT)
        else -> VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_UNREACHABLE)
    }

    /**
     * The code for an HTTP status, or null for success: 401/403 unauthorized, 408/504 timeout,
     * 502/503 unreachable, any other 4xx rejected, any other answer a bad response.
     */
    private fun statusFailure(status: Int): HostVoiceErrorCode? = when (status) {
        in OK_FIRST..OK_LAST -> null
        in failureByStatus -> failureByStatus.getValue(status)
        in CLIENT_ERROR_FIRST..CLIENT_ERROR_LAST -> HostVoiceErrorCode.UPSTREAM_REJECTED
        else -> HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE
    }

    private fun parse(body: String): JsonElement? = runCatching { lenientJson.parseToJsonElement(body) }.getOrNull()

    /**
     * The ids in a listing: a top-level array, or the array under the first of [fields] present; each
     * entry a string or an object with an `id`, `name` or `voice_id` string. Null when the body is not
     * such a listing.
     */
    private fun ids(root: JsonElement?, vararg fields: String): List<String>? {
        val list = root as? JsonArray ?: (root as? JsonObject)?.let { obj -> fields.firstNotNullOfOrNull { obj[it] as? JsonArray } }
        return list?.map { entry -> idOf(entry) ?: return null }
    }

    private fun idOf(entry: JsonElement): String? {
        val primitive = (entry as? JsonPrimitive) ?: (entry as? JsonObject)?.let { obj ->
            ID_FIELDS.firstNotNullOfOrNull { obj[it] as? JsonPrimitive }
        }
        return primitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }

    private companion object {
        val MODELS_ROUTE = listOf("v1", "models")
        val VOICES_ROUTE = listOf("v1", "audio", "voices")
        val TRANSCRIPTIONS_ROUTE = listOf("v1", "audio", "transcriptions")
        val SPEECH_ROUTE = listOf("v1", "audio", "speech")

        /** A `verbose_json` request refused with these is retried as plain `json`. */
        val FORMAT_REFUSED_STATUSES = setOf(400, 415, 422)
        val ID_FIELDS = listOf("id", "name", "voice_id")
        const val VERBOSE_JSON = "verbose_json"
        const val PLAIN_JSON = "json"
        const val WAV = "wav"
        const val WAV_MIME = "audio/wav"
        const val CHUNK_BYTES = 8 * 1024
        val lenientJson = Json { ignoreUnknownKeys = true }
        val failureByStatus = mapOf(
            401 to HostVoiceErrorCode.UPSTREAM_UNAUTHORIZED,
            403 to HostVoiceErrorCode.UPSTREAM_UNAUTHORIZED,
            408 to HostVoiceErrorCode.UPSTREAM_TIMEOUT,
            504 to HostVoiceErrorCode.UPSTREAM_TIMEOUT,
            502 to HostVoiceErrorCode.UPSTREAM_UNREACHABLE,
            503 to HostVoiceErrorCode.UPSTREAM_UNREACHABLE,
        )
        const val OK_FIRST = 200
        const val OK_LAST = 299
        const val CLIENT_ERROR_FIRST = 400
        const val CLIENT_ERROR_LAST = 499
    }
}
