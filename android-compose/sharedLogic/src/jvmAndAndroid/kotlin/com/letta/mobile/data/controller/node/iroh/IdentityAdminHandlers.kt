package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object IdentityAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("identity.list") { api.get("identities") }
        router.register("identity.get") { p -> param(p, "identity_id")?.let { api.get("identities", it) } ?: adminError("identity_id required") }
        router.register("project.list") { api.get("projects") }
        router.register("project.get") { p -> param(p, "project_id")?.let { api.get("projects", it) } ?: adminError("project_id required") }
        router.register("project.create") { p -> api.post("projects", body = p?.toString() ?: "{}") }
        router.register("project.update") { p -> param(p, "project_id")?.let { api.patch("projects", it, body = p.toString()) } ?: adminError("project_id required") }
        router.register("project.delete") { p -> param(p, "project_id")?.let { api.delete("projects", it) } ?: adminError("project_id required") }
    }
}
