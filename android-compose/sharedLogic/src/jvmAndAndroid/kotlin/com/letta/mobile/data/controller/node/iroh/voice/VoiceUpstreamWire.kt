package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the speech server client sends and how it reads the answers: request bodies, the HTTP status
 * map, failure classification and the model and voice listings. No body is ever kept or logged; only
 * the fields named here are read from it.
 */
internal object VoiceUpstreamWire {
    private const val VERBOSE_JSON = "verbose_json"
    private const val PLAIN_JSON = "json"
    private const val WAV = "wav"
    private const val WAV_MIME = "audio/wav"
    private const val OK_FIRST = 200
    private const val OK_LAST = 299
    private const val CLIENT_ERROR_FIRST = 400
    private const val CLIENT_ERROR_LAST = 499
    private val ID_FIELDS = listOf("id", "name", "voice_id")
    private val failureByStatus = mapOf(
        401 to HostVoiceErrorCode.UPSTREAM_UNAUTHORIZED,
        403 to HostVoiceErrorCode.UPSTREAM_UNAUTHORIZED,
        408 to HostVoiceErrorCode.UPSTREAM_TIMEOUT,
        504 to HostVoiceErrorCode.UPSTREAM_TIMEOUT,
        502 to HostVoiceErrorCode.UPSTREAM_UNREACHABLE,
        503 to HostVoiceErrorCode.UPSTREAM_UNREACHABLE,
    )

    /** The multipart transcription request: model, response format, optional language and the WAVE file. */
    fun transcriptionBody(request: VoiceTranscriptionCall): MultiPartFormDataContent {
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

    /** The speech request, asking for WAVE. */
    fun speechBody(request: VoiceSpeechCall): String {
        return buildJsonObject {
            put("model", request.model)
            put("voice", request.voice)
            put("input", request.text)
            put("response_format", WAV)
        }.toString()
    }

    /**
     * The code for an HTTP status, or null for success: 401/403 unauthorized, 408/504 timeout,
     * 502/503 unreachable, any other 4xx rejected, any other answer a bad response.
     */
    fun statusFailure(status: Int): HostVoiceErrorCode? {
        return when (status) {
            in OK_FIRST..OK_LAST -> null
            in failureByStatus -> failureByStatus.getValue(status)
            in CLIENT_ERROR_FIRST..CLIENT_ERROR_LAST -> HostVoiceErrorCode.UPSTREAM_REJECTED
            else -> HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE
        }
    }

    /**
     * A client timeout is `voice.upstream_timeout`; anything else that is not already a
     * [VoiceUpstreamFailure] means the server could not be reached.
     */
    fun classify(error: Exception): Exception {
        return when (error) {
            is VoiceUpstreamFailure -> error
            is HttpRequestTimeoutException, is ConnectTimeoutException, is SocketTimeoutException ->
                VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_TIMEOUT)
            else -> VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_UNREACHABLE)
        }
    }

    /** A body read that failed after the headers: a stall is a timeout, anything else a bad response. */
    fun readFailure(error: Exception, status: Int): VoiceUpstreamFailure {
        val code = if (error is SocketTimeoutException) HostVoiceErrorCode.UPSTREAM_TIMEOUT else HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE
        return VoiceUpstreamFailure(code, status)
    }

    /** [body] as JSON, or null when it is not JSON. */
    fun parse(body: String): JsonElement? {
        return runCatching { Json.parseToJsonElement(body) }.getOrNull()
    }

    /**
     * The ids in a listing: a top-level array, or the array under the first of [fields] present; each
     * entry a string or an object with an `id`, `name` or `voice_id` string. Null when the body is not
     * such a listing.
     */
    fun ids(root: JsonElement?, vararg fields: String): List<String>? {
        val list = root as? JsonArray ?: (root as? JsonObject)?.let { obj -> fields.firstNotNullOfOrNull { obj[it] as? JsonArray } }
        return list?.map { entry -> idOf(entry) ?: return null }
    }

    private fun idOf(entry: JsonElement): String? {
        val primitive = (entry as? JsonPrimitive) ?: (entry as? JsonObject)?.let { obj ->
            ID_FIELDS.firstNotNullOfOrNull { obj[it] as? JsonPrimitive }
        }
        return primitive?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
    }
}
