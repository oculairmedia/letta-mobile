package com.letta.mobile.data.beads

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

internal class RequestSpec(
    val operation: BeadsOperation,
    val pathParameters: Map<String, String>,
    val query: Map<String, List<String>>,
    val body: JsonElement?,
)

internal object BeadsRequests {
    suspend fun request(client: HttpClient, baseUrl: String, token: String?, spec: RequestSpec): JsonElement =
        BeadsFailures.translate {
            client.prepareRequest(requestUrl(baseUrl, spec)) {
                configureRequest(spec, token)
            }.execute { response ->
                BeadsFailures.checkStatus(response)
                decodeResponse(response)
            }
        }

    private fun requestUrl(baseUrl: String, spec: RequestSpec): String {
        var path = spec.operation.path
        spec.pathParameters.forEach { (name, value) -> path = path.replace("{$name}", value.encodeURLPathPart()) }
        return baseUrl.trimEnd('/') + path
    }

    private fun HttpRequestBuilder.configureRequest(spec: RequestSpec, token: String?) {
        method = HttpMethod.parse(spec.operation.method)
        spec.query.forEach { (name, values) -> values.forEach { url.parameters.append(name, it) } }
        token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        spec.body?.let {
            contentType(ContentType.Application.Json)
            setBody(it.toString())
        }
    }

    private suspend fun decodeResponse(response: HttpResponse): JsonElement = try {
        wireJson.parseToJsonElement(response.bodyAsText())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        throw BeadsProtocolException()
    }
}
