package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object IdentityAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = Api(AdminProxyClient(adminBaseUrl))
        router.register("identity.list") { api.get("identities") }
        router.register("identity.get") { p -> id(p, "identity_id")?.let { api.get("identities", it) } ?: adminError("identity_id required") }
        router.register("project.list") { api.get("projects") }
        router.register("project.get") { p -> id(p, "project_id")?.let { api.get("projects", it) } ?: adminError("project_id required") }
        router.register("project.create") { p -> api.post("projects", body = p?.toString() ?: "{}") }
        router.register("project.update") { p -> id(p, "project_id")?.let { api.patch("projects", it, body = p.toString()) } ?: adminError("project_id required") }
        router.register("project.delete") { p -> id(p, "project_id")?.let { api.delete("projects", it) } ?: adminError("project_id required") }
    }

    private class Api(private val proxy: AdminProxyClient) {
        fun get(vararg segments: String): JsonElement = proxy.get(adminProxyRequest("v1", *segments).build())
        fun post(vararg segments: String, body: String): JsonElement = proxy.post(adminProxyRequest("v1", *segments).build(), body)
        fun patch(vararg segments: String, body: String): JsonElement = proxy.patch(adminProxyRequest("v1", *segments).build(), body)
        fun delete(vararg segments: String): JsonElement = proxy.delete(adminProxyRequest("v1", *segments).build())
    }

    private fun id(params: JsonObject?, key: String): String? = params?.get(key)?.jsonPrimitive?.contentOrNull

}
