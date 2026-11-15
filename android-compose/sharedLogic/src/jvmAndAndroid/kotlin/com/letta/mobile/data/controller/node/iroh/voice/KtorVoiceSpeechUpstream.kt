package com.letta.mobile.data.controller.node.iroh.voice

import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.endpoint.SpeechEndpointAddress
import com.letta.mobile.data.voice.endpoint.SpeechTransportPolicy
import com.letta.mobile.data.voice.endpoint.TranscriptResponse
import com.letta.mobile.data.voice.host.HostVoiceErrorCode
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
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
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

    override suspend fun models(target: VoiceUpstreamTarget): List<String> {
        return guarded(timeouts.reachability.inWholeMilliseconds) { listModels(target) }
    }

    override suspend fun voices(target: VoiceUpstreamTarget): List<String>? {
        return guarded(timeouts.reachability.inWholeMilliseconds) { listVoices(target) }
    }

    override suspend fun transcribe(target: VoiceUpstreamTarget, request: VoiceTranscriptionCall): VoiceTranscriptionAnswer {
        return guarded(timeouts.transcription.inWholeMilliseconds) { postTranscription(target, request) }
    }

    private suspend fun listModels(target: VoiceUpstreamTarget): List<String> {
        val response = client.get { aimAt(target, MODELS_ROUTE) }
        requireSuccess(response)
        val models = VoiceUpstreamWire.ids(VoiceUpstreamWire.parse(response.bodyAsText()), "data", "models")
        return models ?: throw VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE)
    }

    private suspend fun listVoices(target: VoiceUpstreamTarget): List<String>? {
        val response = client.get { aimAt(target, VOICES_ROUTE) }
        if (VoiceUpstreamWire.statusFailure(response.status.value) != null) return null
        return VoiceUpstreamWire.ids(VoiceUpstreamWire.parse(response.bodyAsText()), "voices", "data")?.takeIf { it.isNotEmpty() }
    }

    private suspend fun postTranscription(target: VoiceUpstreamTarget, request: VoiceTranscriptionCall): VoiceTranscriptionAnswer {
        val response = client.post {
            aimAt(target, TRANSCRIPTIONS_ROUTE)
            setBody(VoiceUpstreamWire.transcriptionBody(request))
        }
        if (request.verbose && response.status.value in FORMAT_REFUSED_STATUSES) return VoiceTranscriptionAnswer.FormatRefused
        requireSuccess(response)
        val transcript = TranscriptResponse.parse(response.bodyAsText())
            ?: throw VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_BAD_RESPONSE, response.status.value)
        return VoiceTranscriptionAnswer.Heard(transcript)
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
                    setBody(TextContent(VoiceUpstreamWire.speechBody(request), ContentType.Application.Json))
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
    private suspend fun readRun(channel: ByteReadChannel, buffer: ByteArray, status: Int): Int {
        try {
            return channel.readAvailable(buffer, 0, buffer.size)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedBreak: Exception) {
            throw VoiceUpstreamWire.readFailure(expectedBreak, status)
        }
    }

    private fun requireSuccess(response: HttpResponse) {
        VoiceUpstreamWire.statusFailure(response.status.value)?.let { throw VoiceUpstreamFailure(it, response.status.value) }
    }

    /**
     * Runs [block] within [totalMs], mapping every failure to the closed set ([VoiceUpstreamWire.classify]);
     * running out of time is `voice.upstream_timeout`. Cancellation passes through.
     */
    private suspend fun <T> guarded(totalMs: Long, block: suspend () -> T): T {
        val answer = withTimeoutOrNull(totalMs) { Answer(classified(block)) }
        return (answer ?: throw VoiceUpstreamFailure(HostVoiceErrorCode.UPSTREAM_TIMEOUT)).value
    }

    private suspend fun <T> classified(block: suspend () -> T): T {
        try {
            return block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (expectedFailure: Exception) {
            throw VoiceUpstreamWire.classify(expectedFailure)
        }
    }

    /** Holds a result that may itself be null, so a timeout is told apart from a null answer. */
    private class Answer<T>(val value: T)

    private companion object {
        val MODELS_ROUTE = listOf("v1", "models")
        val VOICES_ROUTE = listOf("v1", "audio", "voices")
        val TRANSCRIPTIONS_ROUTE = listOf("v1", "audio", "transcriptions")
        val SPEECH_ROUTE = listOf("v1", "audio", "speech")

        /** A `verbose_json` request refused with these is retried as plain `json`. */
        val FORMAT_REFUSED_STATUSES = setOf(400, 415, 422)
        const val CHUNK_BYTES = 8 * 1024
    }
}
