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

object ConversationAdminHandlers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = ConvApi(adminBaseUrl.trimEnd('/'))
        router.register("conversation.list") { api.list(it) }
        router.register("conversation.get") { api.get(it) }
        router.register("conversation.create") { api.create(it) }
        router.register("conversation.delete") { api.delete(it) }
        router.register("conversation.archive") { api.archive(it) }
        router.register("conversation.restore") { api.restore(it) }
        router.register("message.list") { api.messageList(it) }
        router.register("message.get") { api.messageGet(it) }
    }

    private class ConvApi(private val base: String) {
        fun list(params: JsonObject?): JsonElement {
            val agentId = params?.get("agent_id")?.jsonPrimitive?.contentOrNull
            val queryParams = buildList {
                params?.get("limit")?.jsonPrimitive?.contentOrNull?.let { add("limit=$it") }
                params?.get("after")?.jsonPrimitive?.contentOrNull?.let { add("after=$it") }
                params?.get("archive_status")?.jsonPrimitive?.contentOrNull?.let { add("archive_status=$it") }
                params?.get("summary_search")?.jsonPrimitive?.contentOrNull?.let { add("summary_search=$it") }
                params?.get("order")?.jsonPrimitive?.contentOrNull?.let { add("order=$it") }
                params?.get("order_by")?.jsonPrimitive?.contentOrNull?.let { add("order_by=$it") }
            }
            val query = queryParams.joinToString(prefix = "?", separator = "&").takeIf { queryParams.isNotEmpty() }.orEmpty()
            return if (agentId != null) {
                httpGet("$base/v1/agents/$agentId/conversations$query")
            } else {
                httpGet("$base/v1/conversations$query")
            }
        }

        fun get(params: JsonObject?): JsonElement {
            val id = params?.get("conversation_id")?.jsonPrimitive?.contentOrNull
            return if (id != null) httpGet("$base/v1/conversations/$id") else jsonError("conversation_id required")
        }

        fun create(params: JsonObject?): JsonElement {
            val agentId = params?.get("agent_id")?.jsonPrimitive?.contentOrNull
            return if (agentId != null) httpPost("$base/v1/agents/$agentId/conversations", params.toString())
            else jsonError("agent_id required")
        }

        fun delete(params: JsonObject?): JsonElement {
            val id = params?.get("conversation_id")?.jsonPrimitive?.contentOrNull
            return if (id != null) httpDelete("$base/v1/conversations/$id") else jsonError("conversation_id required")
        }

        fun archive(params: JsonObject?): JsonElement {
            val id = params?.get("conversation_id")?.jsonPrimitive?.contentOrNull
            return if (id != null) httpPatch("$base/v1/conversations/$id/archive", params.toString()) else jsonError("conversation_id required")
        }

        fun restore(params: JsonObject?): JsonElement {
            val id = params?.get("conversation_id")?.jsonPrimitive?.contentOrNull
            return if (id != null) httpPatch("$base/v1/conversations/$id/unarchive", params.toString()) else jsonError("conversation_id required")
        }

        fun messageList(params: JsonObject?): JsonElement {
            val convId = params?.get("conversation_id")?.jsonPrimitive?.contentOrNull ?: return jsonError("conversation_id required")
            val queryParams = buildList {
                params?.get("limit")?.jsonPrimitive?.contentOrNull?.let { add("limit=$it") }
                params?.get("after")?.jsonPrimitive?.contentOrNull?.let { add("after=$it") }
                params?.get("order")?.jsonPrimitive?.contentOrNull?.let { add("order=$it") }
            }
            val query = queryParams.joinToString(prefix = "?", separator = "&").takeIf { queryParams.isNotEmpty() }.orEmpty()
            return httpGet("$base/v1/conversations/$convId/messages$query")
        }

        fun messageGet(params: JsonObject?): JsonElement {
            val convId = params?.get("conversation_id")?.jsonPrimitive?.contentOrNull ?: return jsonError("conversation_id required")
            val msgId = params?.get("message_id")?.jsonPrimitive?.contentOrNull ?: return jsonError("message_id required")
            return httpGet("$base/v1/conversations/$convId/messages/$msgId")
        }

        private fun request(method: String, url: String, body: String? = null): JsonElement {
            val startMs = System.currentTimeMillis()
            return try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = method; conn.connectTimeout = 15_000; conn.readTimeout = 15_000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Accept", "application/json")
                if (body != null) { conn.doOutput = true; OutputStreamWriter(conn.outputStream).use { it.write(body) } }
                val code = conn.responseCode
                val text = if (code in 200..299) conn.inputStream.bufferedReader().readText()
                    else conn.errorStream?.bufferedReader()?.readText() ?: "{\"error\":\"HTTP $code\"}"
                Telemetry.event("AdminRpc", "proxy.$method", "url" to url, "code" to code, "ms" to (System.currentTimeMillis()-startMs))
                json.parseToJsonElement(text)
            } catch (e: Exception) {
                Telemetry.event("AdminRpc", "proxy.failed", "url" to url, "error" to (e.message ?: ""))
                jsonError(e.message ?: e.toString())
            }
        }
        private fun httpGet(u: String) = request("GET", u)
        private fun httpPost(u: String, b: String) = request("POST", u, b)
        private fun httpPatch(u: String, b: String) = request("PATCH", u, b)
        private fun httpDelete(u: String) = request("DELETE", u)
        private fun jsonError(m: String) = buildJsonObject { put("_error", m) }
    }
}
