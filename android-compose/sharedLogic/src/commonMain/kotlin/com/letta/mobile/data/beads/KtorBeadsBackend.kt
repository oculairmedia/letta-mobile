package com.letta.mobile.data.beads

import com.letta.mobile.data.beads.wire.core.ContextResponse
import com.letta.mobile.data.stream.SseParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
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
                val response = request(isolated, baseUrl, token, BeadsOperation.GET_CONTEXT, emptyMap(), emptyMap(), null)
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
        return request(client, baseUrl, token, operation, pathParameters, query, body)
    }

    override fun watchEvents(since: Long, lastEventId: Long?): Flow<BeadsEvent> = flow {
        guard(BeadsOperation.WATCH_EVENTS)
        try {
            client.prepareRequest(baseUrl.trimEnd('/') + BeadsOperation.WATCH_EVENTS.path) {
                method = HttpMethod.Get
                url.parameters.append("since", since.toString())
                lastEventId?.let { header("Last-Event-ID", it.toString()) }
                token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            }.execute { response ->
                checkStatus(response)
                val channel = response.bodyAsChannel()
                try {
                    SseParser.parseRawEvents(channel).collect { frame ->
                        if (frame.event != null && frame.event != "message") throw BeadsProtocolException()
                        val value = try {
                            wireJson.parseToJsonElement(frame.data)
                        } catch (_: Exception) {
                            throw BeadsProtocolException()
                        }
                        emit(decodeBeads(value))
                    }
                } finally {
                    channel.cancel(null)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: BeadsHttpException) {
            throw e
        } catch (e: BeadsProtocolException) {
            throw e
        } catch (_: Exception) {
            throw BeadsTransportException()
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

private suspend fun request(
    client: HttpClient,
    baseUrl: String,
    token: String?,
    operation: BeadsOperation,
    pathParameters: Map<String, String>,
    query: Map<String, List<String>>,
    body: JsonElement?,
): JsonElement = try {
    var path = operation.path
    pathParameters.forEach { (name, value) -> path = path.replace("{$name}", value.encodeURLPathPart()) }
    client.prepareRequest(baseUrl.trimEnd('/') + path) {
        method = HttpMethod.parse(operation.method)
        query.forEach { (name, values) -> values.forEach { url.parameters.append(name, it) } }
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        body?.let {
            contentType(ContentType.Application.Json)
            setBody(it.toString())
        }
    }.execute { response ->
        checkStatus(response)
        try {
            wireJson.parseToJsonElement(response.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw BeadsProtocolException()
        }
    }
} catch (e: CancellationException) {
    throw e
} catch (e: BeadsHttpException) {
    throw e
} catch (e: BeadsProtocolException) {
    throw e
} catch (_: Exception) {
    throw BeadsTransportException()
}

private fun checkStatus(response: HttpResponse) {
    if (response.status.value !in 200..299) throw BeadsHttpException(response.status.value)
}
