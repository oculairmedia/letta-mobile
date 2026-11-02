package com.letta.mobile.data.beads

import com.letta.mobile.data.beads.wire.core.ContextResponse
import com.letta.mobile.data.stream.SseParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement

/** An advertised operation is unavailable on the connected server. */
class BeadsUnsupportedOperation(val capability: String) : Exception("Beads capability unavailable: $capability")

/** An HTTP refusal from bd serve. Response bodies are deliberately excluded. */
class BeadsHttpException(val status: Int) : Exception("Beads HTTP request failed ($status)")

/** A transport failure with no retained request or credential information. */
class BeadsTransportException : Exception("Beads transport failed")

/** A response could not be decoded, or the event journal was truncated. */
class BeadsProtocolException : Exception("Invalid or truncated Beads response")

/** Portable Ktor client. The caller owns the supplied HTTP engine. */
class KtorBeadsBackend private constructor(
    private val client: HttpClient,
    private val baseUrl: String,
    private val token: String?,
    override val context: ContextResponse,
) : BeadsBackend {
    /** Connects and retrieves the server identity before exposing the backend. */
    companion object {
        suspend fun connect(engine: HttpClientEngine, baseUrl: String, token: String? = null): KtorBeadsBackend {
            val isolated = HttpClient(engine) { expectSuccess = false; followRedirects = false }
            var connected = false
            try {
                val spec = RequestSpec(BeadsOperation.GET_CONTEXT, emptyMap(), emptyMap(), null)
                val response = BeadsRequests.request(isolated, baseUrl, token, spec)
                val context: ContextResponse = decodeBeads(response)
                val backend = KtorBeadsBackend(isolated, baseUrl, token, context)
                connected = true
                return backend
            } finally {
                if (!connected) isolated.close()
            }
        }
    }

    override suspend fun execute(
        operation: BeadsOperation,
        pathParameters: Map<String, String>,
        query: Map<String, List<String>>,
        body: JsonElement?,
    ): JsonElement {
        guard(operation)
        return BeadsRequests.request(client, baseUrl, token, RequestSpec(operation, pathParameters, query, body))
    }

    override fun watchEvents(since: Long, lastEventId: Long?): Flow<BeadsEvent> = flow {
        guard(BeadsOperation.WATCH_EVENTS)
        BeadsFailures.translate {
            client.prepareRequest(baseUrl.trimEnd('/') + BeadsOperation.WATCH_EVENTS.path) {
                method = HttpMethod.Get
                url.parameters.append("since", since.toString())
                lastEventId?.let { header("Last-Event-ID", it.toString()) }
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }.execute { response ->
                BeadsFailures.checkStatus(response)
                val channel = response.bodyAsChannel()
                try {
                    SseParser.parseRawEvents(channel).collect { frame ->
                        emit(BeadsEventDecoder.decode(frame.event, frame.data))
                    }
                } finally {
                    channel.cancel(null)
                }
            }
        }
    }

    /** Closes the client and cancels its active requests. */
    fun close() {
        client.close()
    }

    private fun guard(operation: BeadsOperation) {
        val capability = operation.capability
        if (capability != null && capability !in context.capabilities) throw BeadsUnsupportedOperation(capability)
    }
}
