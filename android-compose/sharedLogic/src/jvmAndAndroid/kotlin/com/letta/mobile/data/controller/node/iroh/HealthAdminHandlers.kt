package com.letta.mobile.data.controller.node.iroh
import kotlinx.serialization.json.*
import java.net.HttpURLConnection; import java.net.URL

object HealthAdminHandlers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val base = adminBaseUrl.trimEnd('/')
        router.register("health.check") { try {
            val c = URL("$base/v1/health").openConnection() as HttpURLConnection; c.connectTimeout = 5000; c.readTimeout = 5000
            val code = c.responseCode; val body = if(code in 200..299) c.inputStream.bufferedReader().readText() else "{\"error\":\"HTTP $code\"}"
            json.parseToJsonElement(body)
        } catch(e: Exception) { buildJsonObject { put("status","unreachable"); put("error", e.message ?: e.toString()) } }
        }
    }
}
