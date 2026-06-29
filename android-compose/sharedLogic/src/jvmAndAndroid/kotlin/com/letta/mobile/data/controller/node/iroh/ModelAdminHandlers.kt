package com.letta.mobile.data.controller.node.iroh
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.Json; import kotlinx.serialization.json.JsonElement; import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject; import kotlinx.serialization.json.put
import java.net.HttpURLConnection; import java.net.URL

object ModelAdminHandlers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val base = adminBaseUrl.trimEnd('/')
        router.register("model.list") { httpGet("$base/v1/models") }
        router.register("provider.list") { httpGet("$base/v1/providers") }
    }
    private fun httpGet(url: String): JsonElement = try {
        val c = URL(url).openConnection() as HttpURLConnection; c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("Accept","application/json")
        val code = c.responseCode; val t = if(code in 200..299) c.inputStream.bufferedReader().readText() else "{\"error\":\"HTTP $code\"}"
        Telemetry.event("AdminRpc","proxy.GET","url" to url,"code" to code); json.parseToJsonElement(t)
    } catch(e:Exception){ buildJsonObject { put("_error", e.message ?: e.toString()) } }
}
