package com.letta.mobile.data.voice.endpoint

import com.letta.mobile.data.voice.SpeechEndpointUrl
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.catalog.SpeechCatalog
import com.letta.mobile.data.voice.catalog.SpeechCatalogException
import com.letta.mobile.data.voice.catalog.SpeechCatalogSource
import com.letta.mobile.data.voice.catalog.SpeechListing
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel

/**
 * A speech server's catalog, asked directly: `GET {base}/v1/models`, which every OpenAI-compatible
 * server answers, and `GET {base}/v1/audio/voices`, an extension many add. A missing (404) or failing
 * voice listing leaves the voices unlisted rather than failing the catalog; a failing model listing
 * fails it with the fixed code.
 *
 * The same rules as [SpeechEndpointClient]: the base URL must be plain, a configured key is sent only
 * over https or to an exact loopback host, redirects are never followed, and nothing it throws or
 * records carries the address, the key or the server's reply. No audio or text is sent, so no
 * destination grant is needed.
 */
class EndpointSpeechCatalog(
    engine: HttpClientEngine,
    private val config: SpeechEndpointConfig,
) : SpeechCatalogSource {
    private val client = HttpClient(engine) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            connectTimeoutMillis = config.timeouts.connect.inWholeMilliseconds
            requestTimeoutMillis = config.timeouts.request.inWholeMilliseconds
        }
    }

    override suspend fun load(): SpeechCatalog {
        val outcome = runCatching { SpeechCatalog(models(), voices()) }
        val error = outcome.exceptionOrNull() ?: return outcome.getOrThrow()
        if (error is CancellationException) throw error
        val failure = classifySpeechFailure(SpeechOperation.REACHABILITY, error)
        throw SpeechCatalogException(failure.code, failure)
    }

    /** Cancels the requests in flight and closes the client. */
    fun close() {
        client.coroutineContext.cancel()
        client.close()
    }

    private suspend fun models(): List<String> {
        val response = get(MODELS_ROUTE)
        val status = response.status.value
        SpeechStatusCodes.failureFor(status)?.let { throw SpeechEndpointException(SpeechOperation.REACHABILITY, it, status) }
        return SpeechListing.modelIds(response.bodyAsText())
            ?: throw SpeechEndpointException(SpeechOperation.REACHABILITY, VoiceErrorCode.BAD_RESPONSE, status)
    }

    /** The listed voices, or null when the server lists none, answers with a failure or cannot be read. */
    private suspend fun voices(): List<String>? {
        val outcome = runCatching {
            val response = get(VOICES_ROUTE)
            if (SpeechStatusCodes.failureFor(response.status.value) != null) null else SpeechListing.voiceIds(response.bodyAsText())
        }
        outcome.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return outcome.getOrNull()
    }

    private suspend fun get(route: List<String>): HttpResponse {
        val target = SpeechEndpointAddress.resolve(config.baseUrl, route)
            ?: throw SpeechEndpointException(SpeechOperation.REACHABILITY, VoiceErrorCode.INVALID_ENDPOINT)
        if (config.apiKey != null && !SpeechTransportPolicy.protectsKey(target)) {
            throw SpeechEndpointException(SpeechOperation.REACHABILITY, VoiceErrorCode.INSECURE_TRANSPORT)
        }
        return client.get {
            url(target)
            config.apiKey?.let { header(HttpHeaders.Authorization, it.bearer()) }
        }
    }

    /** Routes, and the one-shot listing. */
    companion object {
        private val MODELS_ROUTE = listOf("v1", "models")
        private val VOICES_ROUTE = listOf("v1", "audio", "voices")

        /**
         * Lists the server at [baseUrl] with [apiKey] (none when null or blank) over [engine], then
         * closes both. Fails with [SpeechCatalogException].
         */
        suspend fun list(baseUrl: String, apiKey: String?, engine: HttpClientEngine): SpeechCatalog {
            val key = apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let(::SpeechApiKey)
            val catalog = EndpointSpeechCatalog(engine, SpeechEndpointConfig(SpeechEndpointUrl(baseUrl.trim()), key))
            return try {
                catalog.load()
            } finally {
                catalog.close()
                engine.close()
            }
        }
    }
}
