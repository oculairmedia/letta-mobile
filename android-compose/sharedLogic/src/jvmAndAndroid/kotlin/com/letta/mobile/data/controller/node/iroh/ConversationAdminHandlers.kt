package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive

object ConversationAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = ConvApi(AdminProxyClient(adminBaseUrl))
        router.register("conversation.list") { api.list(it) }
        router.register("conversation.get") { api.get(it) }
        router.register("conversation.create") { api.create(it) }
        router.register("conversation.delete") { api.delete(it) }
        router.register("conversation.archive") { api.archive(it) }
        router.register("conversation.restore") { api.restore(it) }
        router.register("message.list") { api.messageList(it) }
        router.register("message.get") { api.messageGet(it) }
    }

    private class ConvApi(private val proxy: AdminProxyClient) {
        fun list(params: JsonObject?): JsonElement {
            val agentId = param(params, "agent_id")
            val request = if (agentId != null) {
                adminProxyRequest("v1", "agents", agentId, "conversations")
            } else {
                adminProxyRequest("v1", "conversations")
            }
                .query("limit", param(params, "limit"))
                .query("after", param(params, "after"))
                .query("archive_status", param(params, "archive_status"))
                .query("summary_search", param(params, "summary_search"))
                .query("order", param(params, "order"))
                .query("order_by", param(params, "order_by"))
                .build()
            return proxy.get(request)
        }

        fun get(params: JsonObject?): JsonElement {
            val id = param(params, "conversation_id") ?: return jsonError("conversation_id required")
            return proxy.get(adminProxyRequest("v1", "conversations", id).build())
        }

        fun create(params: JsonObject?): JsonElement {
            val agentId = param(params, "agent_id") ?: return jsonError("agent_id required")
            return proxy.post(adminProxyRequest("v1", "agents", agentId, "conversations").build(), params.toString())
        }

        fun delete(params: JsonObject?): JsonElement {
            val id = param(params, "conversation_id") ?: return jsonError("conversation_id required")
            return proxy.delete(adminProxyRequest("v1", "conversations", id).build())
        }

        fun archive(params: JsonObject?): JsonElement {
            val id = param(params, "conversation_id") ?: return jsonError("conversation_id required")
            return proxy.patch(adminProxyRequest("v1", "conversations", id, "archive").build(), params.toString())
        }

        fun restore(params: JsonObject?): JsonElement {
            val id = param(params, "conversation_id") ?: return jsonError("conversation_id required")
            return proxy.patch(adminProxyRequest("v1", "conversations", id, "unarchive").build(), params.toString())
        }

        fun messageList(params: JsonObject?): JsonElement {
            val convId = param(params, "conversation_id") ?: return jsonError("conversation_id required")
            return proxy.get(
                adminProxyRequest("v1", "conversations", convId, "messages")
                    .query("limit", param(params, "limit"))
                    .query("after", param(params, "after"))
                    .query("order", param(params, "order"))
                    .build(),
            )
        }

        fun messageGet(params: JsonObject?): JsonElement {
            val convId = param(params, "conversation_id") ?: return jsonError("conversation_id required")
            val msgId = param(params, "message_id") ?: return jsonError("message_id required")
            return proxy.get(adminProxyRequest("v1", "conversations", convId, "messages", msgId).build())
        }
    }

    private fun param(params: JsonObject?, key: String): String? = params?.get(key)?.jsonPrimitive?.contentOrNull
    private fun jsonError(message: String): JsonElement = buildJsonObject { put("_error", message) }
}
