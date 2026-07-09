package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object RunAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = Api(AdminProxyClient(adminBaseUrl))
        router.register("run.list") { api.get("runs") }
        router.register("run.get") { p -> id(p, "run_id")?.let { api.get("runs", it) } ?: jsonError("run_id required") }
        router.register("step.list") { p -> id(p, "run_id")?.let { api.get("runs", it, "steps") } ?: jsonError("run_id required") }
    }

    private class Api(private val proxy: AdminProxyClient) {
        fun get(vararg segments: String): JsonElement = proxy.get(adminProxyRequest("v1", *segments).build())
    }

    private fun id(params: JsonObject?, key: String): String? = params?.get(key)?.jsonPrimitive?.contentOrNull
    private fun jsonError(message: String): JsonElement = buildJsonObject { put("_error", message) }
}
