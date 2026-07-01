package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Agent CRUD handlers for the Iroh admin RPC router.
 * Uses java.net.URL for HTTP proxying (no Ktor dependency needed on JVM).
 *
 * Register at server startup:
 * ```
 * AgentAdminHandlers.register(router, "http://localhost:8291")
 * ```
 */
object AgentAdminHandlers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AgentApi(adminBaseUrl.trimEnd('/'))
        router.register("agent.list") { api.list() }
        router.register("agent.get") { params -> api.get(params) }
        router.register("agent.create") { params -> api.create(params) }
        router.register("agent.update") { params -> api.update(params) }
        router.register("agent.delete") { params -> api.delete(params) }
    }

    private class AgentApi(private val base: String) {
        fun list(): JsonElement = httpGet("$base/v1/agents")
        fun get(params: JsonObject?): JsonElement {
            val id = params?.get("agent_id")?.jsonPrimitive?.content
                ?: return jsonError("agent_id required")
            return httpGet("$base/v1/agents/$id")
        }
        fun create(params: JsonObject?): JsonElement = httpPost("$base/v1/agents", params?.toString() ?: "{}")
        fun update(params: JsonObject?): JsonElement {
            val id = params?.get("agent_id")?.jsonPrimitive?.content
                ?: return jsonError("agent_id required")
            return httpPatch("$base/v1/agents/$id", params.toString())
        }
        fun delete(params: JsonObject?): JsonElement {
            val id = params?.get("agent_id")?.jsonPrimitive?.content
                ?: return jsonError("agent_id required")
            return httpDelete("$base/v1/agents/$id")
        }

        private fun httpGet(url: String): JsonElement = request("GET", url)
        private fun httpPost(url: String, body: String): JsonElement = request("POST", url, body)
        private fun httpPatch(url: String, body: String): JsonElement = request("PATCH", url, body)
        private fun httpDelete(url: String): JsonElement = request("DELETE", url)

        private fun request(method: String, url: String, body: String? = null): JsonElement {
            val startMs = System.currentTimeMillis()
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
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
                val responseText = if (code in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    conn.errorStream?.bufferedReader()?.readText() ?: "{\"error\":\"HTTP $code\"}"
                }
                val elapsed = System.currentTimeMillis() - startMs
                Telemetry.event("AdminRpc", "proxy.$method", "url" to url, "code" to code, "ms" to elapsed)

                json.parseToJsonElement(responseText)
            } catch (e: Exception) {
                Telemetry.event("AdminRpc", "proxy.failed", "url" to url, "error" to (e.message ?: ""))
                jsonError(e.message ?: e.toString())
            }
        }

        private fun jsonError(msg: String): JsonElement =
            buildJsonObject { put("_error", msg) }
    }
}
