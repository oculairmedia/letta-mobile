package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * HTTP proxy response wrapping both the HTTP status code and body.
 * Callers can check [status] to distinguish 2xx success from 4xx/5xx errors,
 * then parse [body] for the actual payload.
 */
@Serializable
data class ProxyResponse(
    val status: Int,
    val body: JsonElement,
    val ok: Boolean = status in 200..299,
)

/**
 * Shared HTTP proxy client for admin RPC handlers.
 *
 * Reduces boilerplate across 11 handler files by centralizing:
 * - Connection creation and cleanup
 * - Timeout configuration
 * - Error handling and telemetry
 * - Request body writing
 *
 * Usage:
 * ```
 * val proxy = HttpProxyClient("http://localhost:8291")
 * val agents = proxy.get("/v1/agents")
 * val created = proxy.post("/v1/agents", params.toString())
 * ```
 */
class HttpProxyClient(private val adminBaseUrl: String) {
    private val base = adminBaseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun get(path: String): ProxyResponse = request("GET", path)
    fun post(path: String, body: String = "{}"): ProxyResponse = request("POST", path, body)
    fun patch(path: String, body: String): ProxyResponse = request("PATCH", path, body)
    fun delete(path: String): ProxyResponse = request("DELETE", path)

    fun param(params: JsonObject?, key: String): String? =
        params?.get(key)?.jsonPrimitive?.contentOrNull

    fun requireParam(params: JsonObject?, key: String): String? =
        param(params, key)

    fun error(message: String): ProxyResponse =
        ProxyResponse(status = 500, body = buildJsonObject { put("_error", message) }, ok = false)

    fun error(status: Int, message: String): ProxyResponse =
        ProxyResponse(status = status, body = buildJsonObject { put("_error", message) }, ok = false)

    fun agentPath(agentId: String, resource: String = ""): String =
        "$base/v1/agents/$agentId${if (resource.isNotEmpty()) "/$resource" else ""}"

    fun convPath(convId: String, resource: String = ""): String =
        "$base/v1/conversations/$convId${if (resource.isNotEmpty()) "/$resource" else ""}"

    private fun request(method: String, path: String, body: String? = null): ProxyResponse {
        val url = if (path.startsWith("http")) path else "$base$path"
        val startMs = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                OutputStreamWriter(conn.outputStream).use { it.write(body) }
            }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "{\"error\":\"HTTP $code\"}"
            }
            Telemetry.event("AdminRpc", "proxy.$method",
                "path" to path, "code" to code, "ms" to (System.currentTimeMillis() - startMs))
            ProxyResponse(status = code, body = json.parseToJsonElement(text))
        } catch (e: Exception) {
            Telemetry.event("AdminRpc", "proxy.failed",
                "path" to path, "error" to (e.message ?: ""))
            error(e.message ?: e.toString())
        } finally {
            conn?.disconnect()
        }
    }
}
