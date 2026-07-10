package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object RunAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("run.list") { api.get("runs") }
        router.register("run.get") { p -> param(p, "run_id")?.let { api.get("runs", it) } ?: adminError("run_id required") }
        router.register("step.list") { p -> param(p, "run_id")?.let { api.get("runs", it, "steps") } ?: adminError("run_id required") }
    }
}
