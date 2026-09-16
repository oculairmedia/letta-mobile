package com.letta.mobile.data.transport.appserver

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/**
 * The App Server's HTTP surface beside `/ws`: health probes and capability discovery. Lets a
 * client check a server before opening a socket, which the WebSocket `app_server_info` cannot.
 *
 * - `GET /readyz`, `GET /healthz` need no auth and must not send an Origin header.
 * - `GET /app-server-info` needs `Authorization: Bearer` when the server runs with WS auth, and
 *   answers with the same body as the WebSocket `app_server_info_response`.
 *
 * [baseUrl] may be the `ws(s)://` listen URL or the `http(s)://` origin; a trailing `/ws` or query
 * is dropped. Probe failures read as not ready rather than throwing.
 */
class AppServerDiscovery(private val http: HttpClient) {
    suspend fun readyz(baseUrl: String): Boolean = probe(baseUrl, "/readyz")

    suspend fun healthz(baseUrl: String): Boolean = probe(baseUrl, "/healthz")

    /** The server's info, or an unsuccessful response carrying the HTTP status as its error. */
    suspend fun appServerInfo(baseUrl: String, bearerToken: String? = null): AppServerInboundFrame.AppServerInfoResponse {
        val response = http.get(httpOrigin(baseUrl) + "/app-server-info") {
            bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            return AppServerInboundFrame.AppServerInfoResponse(
                requestId = HTTP_REQUEST_ID,
                success = false,
                error = "app-server-info HTTP ${response.status.value}",
            )
        }
        val raw = AppServerProtocol.json.parseToJsonElement(body) as? JsonObject
            ?: return AppServerInboundFrame.AppServerInfoResponse(HTTP_REQUEST_ID, success = false, error = "app-server-info body is not an object")
        // The body has the frame's shape but no request_id of ours; give it one so it decodes.
        val framed = JsonObject(raw + mapOf("request_id" to kotlinx.serialization.json.JsonPrimitive(HTTP_REQUEST_ID)))
        return AppServerProtocol.json.decodeFromJsonElement(AppServerInboundFrame.serializer(), framed)
            as? AppServerInboundFrame.AppServerInfoResponse
            ?: AppServerInboundFrame.AppServerInfoResponse(HTTP_REQUEST_ID, success = false, error = "app-server-info body is not an info response")
    }

    private suspend fun probe(baseUrl: String, path: String): Boolean =
        try {
            http.get(httpOrigin(baseUrl) + path).status.isSuccess()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    companion object {
        private const val HTTP_REQUEST_ID = "http-app-server-info"

        /** `ws://host:4500/ws?x` → `http://host:4500`; `https://host` stays as it is. */
        fun httpOrigin(baseUrl: String): String {
            val withScheme = when {
                baseUrl.startsWith("wss://") -> "https://" + baseUrl.removePrefix("wss://")
                baseUrl.startsWith("ws://") -> "http://" + baseUrl.removePrefix("ws://")
                baseUrl.startsWith("http://") || baseUrl.startsWith("https://") -> baseUrl
                else -> throw IllegalArgumentException("App Server URL must be ws(s):// or http(s)://: $baseUrl")
            }
            val schemeEnd = withScheme.indexOf("://") + 3
            val pathStart = withScheme.indexOfAny(charArrayOf('/', '?', '#'), schemeEnd).takeIf { it >= 0 } ?: withScheme.length
            return withScheme.substring(0, pathStart)
        }
    }
}
