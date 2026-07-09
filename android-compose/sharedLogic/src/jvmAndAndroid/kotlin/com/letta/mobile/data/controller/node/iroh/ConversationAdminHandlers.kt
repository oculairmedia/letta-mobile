package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object ConversationAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val proxy = AdminHandlerProxy(AdminProxyClient(adminBaseUrl))
        val api = ConvApi(proxy)
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

    private class ConvApi(private val proxy: AdminHandlerProxy) {
        fun list(params: JsonObject?): JsonElement {
            val agentId = AdminHandlerSupport.param(params, "agent_id")
            val req = if (agentId != null) {
                adminProxyRequest("v1", "agents", agentId, "conversations")
            } else {
                adminProxyRequest("v1", "conversations")
            }
                .query("limit", AdminHandlerSupport.param(params, "limit"))
                .query("after", AdminHandlerSupport.param(params, "after"))
                .query("archive_status", AdminHandlerSupport.param(params, "archive_status"))
                .query("summary_search", AdminHandlerSupport.param(params, "summary_search"))
                .query("order", AdminHandlerSupport.param(params, "order"))
                .query("order_by", AdminHandlerSupport.param(params, "order_by"))
                .build()
            return proxy.proxy.get(req)
        }

        fun get(params: JsonObject?): JsonElement {
            val id = AdminHandlerSupport.param(params, "conversation_id") ?: adminError("conversation_id required")
            return proxy.get("v1", "conversations", id)
        }

        fun create(params: JsonObject?): JsonElement {
            val agentId = AdminHandlerSupport.param(params, "agent_id") ?: adminError("agent_id required")
            return proxy.post("v1", "agents", agentId, "conversations", body = params.toString())
        }

        fun delete(params: JsonObject?): JsonElement {
            val id = AdminHandlerSupport.param(params, "conversation_id") ?: adminError("conversation_id required")
            return proxy.delete("v1", "conversations", id)
        }

        // Letta has no /conversations/{id}/archive|/unarchive sub-resource; archive
        // state is a field on the conversation, toggled via PATCH /v1/conversations/{id}
        // with {"archived": bool} (same route + response the HTTP client uses in
        // ConversationApi.updateConversation). That PATCH returns the updated
        // Conversation, which IrohAdminRpcConversationListSource decodes. Hitting a
        // phantom /archive sub-route would 404 and break iroh-mode archive/restore.
        fun archive(params: JsonObject?): JsonElement {
            val id = AdminHandlerSupport.param(params, "conversation_id") ?: adminError("conversation_id required")
            return proxy.patch("v1", "conversations", id, body = """{"archived":true}""")
        }

        fun restore(params: JsonObject?): JsonElement {
            val id = AdminHandlerSupport.param(params, "conversation_id") ?: adminError("conversation_id required")
            return proxy.patch("v1", "conversations", id, body = """{"archived":false}""")
        }

        fun messageList(params: JsonObject?): JsonElement {
            val convId = AdminHandlerSupport.param(params, "conversation_id") ?: adminError("conversation_id required")
            val response = fetchMessages(convId, params)
            // letta-mobile-fe51r (P2b pointer diet): list responses ship
            // previews for heavy tool-return bodies; full bodies come via
            // tool_return.get on demand. Inline attachments ship unmodified
            // (clients have no refetch path for omitted attachment data).
            return MessageListWireProjection.projectMessageList(response, convId)
        }

        private fun fetchMessages(convId: String, params: JsonObject?): JsonElement {
            return proxy.get("v1", "conversations", convId, "messages") {
                query("limit", AdminHandlerSupport.param(params, "limit"))
                query("after", AdminHandlerSupport.param(params, "after"))
                // letta-mobile-71orq: backward pagination (scroll up for
                // history) cursors on `before`; the raw HTTP path
                // (MessageApi.fetchRecentMessages) hits the same endpoint
                // with a `before` query param, so pass it through so
                // iroh:// older-message loads mirror HTTP.
                query("before", AdminHandlerSupport.param(params, "before"))
                query("order", AdminHandlerSupport.param(params, "order"))
            }
        }

        fun messageGet(params: JsonObject?): JsonElement {
            val convId = AdminHandlerSupport.param(params, "conversation_id") ?: adminError("conversation_id required")
            val msgId = AdminHandlerSupport.param(params, "message_id") ?: adminError("message_id required")
            return proxy.get("v1", "conversations", convId, "messages", msgId)
        }

        /**
         * letta-mobile-fe51r: on-demand full-body fetch for a projected
         * tool-return message. Returns the complete, unprojected message.
         */
        fun toolReturnGet(params: JsonObject?): JsonElement {
            val convId = AdminHandlerSupport.param(params, "conversation_id") ?: adminError("conversation_id required")
            val msgId = AdminHandlerSupport.param(params, "message_id") ?: adminError("message_id required")
            return proxy.get("v1", "conversations", convId, "messages", msgId)
        }
    }
}
