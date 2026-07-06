package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
        router.register("tool_return.get") { api.toolReturnGet(it) }
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

        // Letta has no /conversations/{id}/archive|/unarchive sub-resource; archive
        // state is a field on the conversation, toggled via PATCH /v1/conversations/{id}
        // with {"archived": bool} (same route + response the HTTP client uses in
        // ConversationApi.updateConversation). That PATCH returns the updated
        // Conversation, which IrohAdminRpcConversationListSource decodes. Hitting a
        // phantom /archive sub-route would 404 and break iroh-mode archive/restore.
        fun archive(params: JsonObject?): JsonElement {
            val id = param(params, "conversation_id") ?: return jsonError("conversation_id required")
            return proxy.patch(adminProxyRequest("v1", "conversations", id).build(), """{"archived":true}""")
        }

        fun restore(params: JsonObject?): JsonElement {
            val id = param(params, "conversation_id") ?: return jsonError("conversation_id required")
            return proxy.patch(adminProxyRequest("v1", "conversations", id).build(), """{"archived":false}""")
        }

        fun messageList(params: JsonObject?): JsonElement {
            val convId = param(params, "conversation_id") ?: return jsonError("conversation_id required")
            val response = proxy.get(
                adminProxyRequest("v1", "conversations", convId, "messages")
                    .query("limit", param(params, "limit"))
                    .query("after", param(params, "after"))
                    // letta-mobile-71orq: backward pagination (scroll up for
                    // history) cursors on `before`; the raw HTTP path
                    // (MessageApi.fetchRecentMessages) hits the same endpoint
                    // with a `before` query param, so pass it through so
                    // iroh:// older-message loads mirror HTTP.
                    .query("before", param(params, "before"))
                    .query("order", param(params, "order"))
                    .build(),
            )
            // letta-mobile-fe51r (P2b pointer diet): list responses ship
            // previews for heavy tool-return bodies; full bodies come via
            // tool_return.get on demand. Inline attachments ship unmodified
            // (clients have no refetch path for omitted attachment data).
            return MessageListWireProjection.projectMessageList(response, convId)
        }

        fun messageGet(params: JsonObject?): JsonElement {
            val convId = param(params, "conversation_id") ?: return jsonError("conversation_id required")
            val msgId = param(params, "message_id") ?: return jsonError("message_id required")
            return proxy.get(adminProxyRequest("v1", "conversations", convId, "messages", msgId).build())
        }

        /**
         * letta-mobile-fe51r: on-demand full-body fetch for a projected
         * tool-return message. Returns the complete, unprojected message.
         */
        fun toolReturnGet(params: JsonObject?): JsonElement {
            val convId = param(params, "conversation_id") ?: return jsonError("conversation_id required")
            val msgId = param(params, "message_id") ?: return jsonError("message_id required")
            return proxy.get(adminProxyRequest("v1", "conversations", convId, "messages", msgId).build())
        }
    }

    private fun param(params: JsonObject?, key: String): String? = params?.get(key)?.jsonPrimitive?.contentOrNull

    /**
     * letta-mobile-8vplf: handler-level parameter errors previously returned a
     * `{_error: ...}` object that the router wrapped in a `success: true`
     * envelope, so clients decoded an error as a successful result. Throwing
     * here routes the failure through [AdminRpcRouter.dispatch]'s catch path,
     * which encodes a proper `success: false` + `error` envelope. Other
     * handler files still carry private `{_error}` helpers — tracked by bead
     * letta-mobile-8vplf.
     */
    private fun jsonError(message: String): Nothing = throw IllegalArgumentException(message)
}
