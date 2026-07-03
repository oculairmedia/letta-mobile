package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
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

object GoalAdminHandlers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = GoalApi(adminBaseUrl.trimEnd('/'))
        router.register("goal.get") { api.get(it) }
        router.register("goal.command") { api.command(it) }
    }

    private class GoalApi(private val base: String) {
        fun get(params: JsonObject?): JsonElement {
            val agentId = params?.get("agent_id")?.jsonPrimitive?.contentOrNull
                ?: return jsonError("agent_id required")
            return httpGet("$base/v1/agents/$agentId/goal")
        }

        fun command(params: JsonObject?): JsonElement {
            val agentId = params?.get("agent_id")?.jsonPrimitive?.contentOrNull
                ?: return jsonError("agent_id required")
            val command = params["command"]?.jsonPrimitive?.contentOrNull
                ?: return jsonError("command required")
            val body = buildJsonObject { put("command", command) }.toString()
            return httpPost("$base/v1/agents/$agentId/goal/command", body)
        }

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
                val text = if (code in 200..299) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    conn.errorStream?.bufferedReader()?.readText() ?: "{\"error\":\"HTTP $code\"}"
                }
                Telemetry.event("AdminRpc", "proxy.$method", "url" to url, "code" to code, "ms" to (System.currentTimeMillis() - startMs))
                json.parseToJsonElement(text)
            } catch (e: Exception) {
                Telemetry.event("AdminRpc", "proxy.failed", "url" to url, "error" to (e.message ?: ""))
                jsonError(e.message ?: e.toString())
            }
        }

        private fun httpGet(url: String): JsonElement = request("GET", url)
        private fun httpPost(url: String, body: String): JsonElement = request("POST", url, body)
        private fun jsonError(message: String): JsonElement = buildJsonObject { put("_error", message) }
    }
}
