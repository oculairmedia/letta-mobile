package com.letta.mobile.data.controller.node.iroh
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.*
import java.io.OutputStreamWriter; import java.net.HttpURLConnection; import java.net.URL

object McpAdminHandlers {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = Api(adminBaseUrl.trimEnd('/'))
        router.register("mcp.list") { api.httpGet("${api.base}/v1/mcp/servers") }
        router.register("passage.list") { p -> val a = api.param(p, "agent_id"); if(a!=null) api.httpGet("${api.base}/v1/agents/$a/passages") else api.err("agent_id required") }
    }
    private class Api(val base: String) {
        fun param(p: JsonObject?, k: String) = p?.get(k)?.jsonPrimitive?.contentOrNull
        fun err(m: String) = buildJsonObject { put("_error", m) }
        fun httpGet(u: String) = req("GET",u)
        private fun req(m:String,u:String,b:String?=null):JsonElement = try {
            val c=URL(u).openConnection() as HttpURLConnection; c.requestMethod=m; c.connectTimeout=15000; c.readTimeout=15000
            c.setRequestProperty("Content-Type","application/json"); c.setRequestProperty("Accept","application/json")
            if(b!=null){c.doOutput=true;OutputStreamWriter(c.outputStream).use{it.write(b)}}
            val code=c.responseCode; val t=if(code in 200..299)c.inputStream.bufferedReader().readText() else c.errorStream?.bufferedReader()?.readText()?:"{\"error\":\"HTTP $code\"}"
            Telemetry.event("AdminRpc","proxy.$m","url" to u,"code" to code); json.parseToJsonElement(t)
        }catch(e:Exception){err(e.message?:e.toString())}
    }
}
