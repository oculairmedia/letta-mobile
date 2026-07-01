package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.Json; import kotlinx.serialization.json.JsonElement; import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject; import kotlinx.serialization.json.contentOrNull; import kotlinx.serialization.json.put; import kotlinx.serialization.json.jsonPrimitive
import java.io.OutputStreamWriter; import java.net.HttpURLConnection; import java.net.URL

object ToolAdminHandlers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = Api(adminBaseUrl.trimEnd('/'))
        router.register("tool.list") { api.list(it) }
        router.register("tool.get") { api.get(it) }
        router.register("tool.create") { api.create(it) }
        router.register("tool.update") { api.update(it) }
        router.register("tool.delete") { api.delete(it) }
        router.register("block.list") { api.blockList(it) }
        router.register("block.get") { api.blockGet(it) }
        router.register("block.create") { api.blockCreate(it) }
        router.register("block.update") { api.blockUpdate(it) }
        router.register("block.delete") { api.blockDelete(it) }
    }
    private class Api(private val base: String) {
        fun list(p: JsonObject?) = httpGet("$base/v1/tools")
        fun get(p: JsonObject?) = idOrError(p, "tool_id")?.let { httpGet("$base/v1/tools/$it") } ?: jsonError("tool_id required")
        fun create(p: JsonObject?) = httpPost("$base/v1/tools", p?.toString() ?: "{}")
        fun update(p: JsonObject?) = idOrError(p, "tool_id")?.let { httpPatch("$base/v1/tools/$it", p.toString()) } ?: jsonError("tool_id required")
        fun delete(p: JsonObject?) = idOrError(p, "tool_id")?.let { httpDelete("$base/v1/tools/$it") } ?: jsonError("tool_id required")
        fun blockList(p: JsonObject?) = httpGet("$base/v1/blocks")
        fun blockGet(p: JsonObject?) = idOrError(p, "block_id")?.let { httpGet("$base/v1/blocks/$it") } ?: jsonError("block_id required")
        fun blockCreate(p: JsonObject?) = httpPost("$base/v1/blocks", p?.toString() ?: "{}")
        fun blockUpdate(p: JsonObject?) = idOrError(p, "block_id")?.let { httpPatch("$base/v1/blocks/$it", p.toString()) } ?: jsonError("block_id required")
        fun blockDelete(p: JsonObject?) = idOrError(p, "block_id")?.let { httpDelete("$base/v1/blocks/$it") } ?: jsonError("block_id required")
        private fun idOrError(p: JsonObject?, key: String) = p?.get(key)?.jsonPrimitive?.contentOrNull
        private fun request(m: String, u: String, b: String? = null): JsonElement = try {
            val c = URL(u).openConnection() as HttpURLConnection; c.requestMethod = m; c.connectTimeout = 15000; c.readTimeout = 15000
            c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("Accept","application/json")
            if (b != null) { c.doOutput = true; OutputStreamWriter(c.outputStream).use { it.write(b) } }
            val code = c.responseCode; val t = if (code in 200..299) c.inputStream.bufferedReader().readText() else c.errorStream?.bufferedReader()?.readText() ?: "{\"error\":\"HTTP $code\"}"
            Telemetry.event("AdminRpc","proxy.$m","url" to u,"code" to code)
            json.parseToJsonElement(t)
        } catch (e: Exception) { jsonError(e.message ?: e.toString()) }
        private fun httpGet(u: String) = request("GET",u); private fun httpPost(u:String,b:String) = request("POST",u,b)
        private fun httpPatch(u:String,b:String) = request("PATCH",u,b); private fun httpDelete(u:String) = request("DELETE",u)
        private fun jsonError(m:String) = buildJsonObject { put("_error",m) }
    }
}
