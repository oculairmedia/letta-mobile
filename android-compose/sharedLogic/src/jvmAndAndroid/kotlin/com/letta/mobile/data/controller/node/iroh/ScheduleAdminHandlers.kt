package com.letta.mobile.data.controller.node.iroh
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.*
import java.io.OutputStreamWriter
import java.net.HttpURLConnection; import java.net.URL

object ScheduleAdminHandlers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = Api(adminBaseUrl.trimEnd('/'))
        router.register("schedule.list") { api.httpGet("${api.base}/v1/schedules") }
        router.register("schedule.get") { p -> val id = api.param(p, "schedule_id"); if (id != null) api.httpGet("${api.base}/v1/schedules/$id") else api.err("schedule_id required") }
        router.register("schedule.create") { p -> api.httpPost("${api.base}/v1/schedules", p?.toString() ?: "{}") }
        router.register("schedule.delete") { p -> val id = api.param(p, "schedule_id"); if (id != null) api.httpDelete("${api.base}/v1/schedules/$id") else api.err("schedule_id required") }
        router.register("job.list") { api.httpGet("${api.base}/v1/jobs") }
        router.register("job.get") { p -> val id = api.param(p, "job_id"); if (id != null) api.httpGet("${api.base}/v1/jobs/$id") else api.err("job_id required") }
    }
    private class Api(val base: String) {
        fun param(p: JsonObject?, key: String) = p?.get(key)?.jsonPrimitive?.contentOrNull
        fun err(m: String) = buildJsonObject { put("_error", m) }
        fun httpGet(u: String) = req("GET", u)
        fun httpPost(u: String, b: String) = req("POST", u, b)
        fun httpDelete(u: String) = req("DELETE", u)
        private fun req(m: String, u: String, b: String? = null): JsonElement = try {
            val c = URL(u).openConnection() as HttpURLConnection; c.requestMethod = m; c.connectTimeout = 15000; c.readTimeout = 15000
            c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("Accept","application/json")
            if (b != null) { c.doOutput = true; OutputStreamWriter(c.outputStream).use { it.write(b) } }
            val code = c.responseCode; val t = if (code in 200..299) c.inputStream.bufferedReader().readText()
                else c.errorStream?.bufferedReader()?.readText() ?: "{\"error\":\"HTTP $code\"}"
            Telemetry.event("AdminRpc","proxy.$m","url" to u,"code" to code); json.parseToJsonElement(t)
        } catch (e: Exception) { buildJsonObject { put("_error", e.message ?: e.toString()) } }
    }
}
