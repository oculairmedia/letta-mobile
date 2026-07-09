package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object IdentityAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val proxy = AdminHandlerProxy(AdminProxyClient(adminBaseUrl))
        router.register("identity.list") { proxy.get("v1", "identities") }
        router.register("identity.get") { p -> AdminHandlerSupport.param(p, "identity_id")?.let { proxy.get("v1", "identities", it) } ?: adminError("identity_id required") }
        router.register("project.list") { proxy.get("v1", "projects") }
        router.register("project.get") { p -> AdminHandlerSupport.param(p, "project_id")?.let { proxy.get("v1", "projects", it) } ?: adminError("project_id required") }
        router.register("project.create") { p -> proxy.post("v1", "projects", body = p?.toString() ?: "{}") }
        router.register("project.update") { p -> AdminHandlerSupport.param(p, "project_id")?.let { proxy.patch("v1", "projects", it, body = p.toString()) } ?: adminError("project_id required") }
        router.register("project.delete") { p -> AdminHandlerSupport.param(p, "project_id")?.let { proxy.delete("v1", "projects", it) } ?: adminError("project_id required") }
    }
}
