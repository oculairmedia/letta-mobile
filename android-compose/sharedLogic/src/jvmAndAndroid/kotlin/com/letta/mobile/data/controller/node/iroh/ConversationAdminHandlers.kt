package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object ConversationAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("conversation.list") { params ->
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
            api.get(request)
        }
        router.register("conversation.get") { params ->
            val id = param(params, "conversation_id") ?: return@register adminError("conversation_id required")
            api.get("conversations", id)
        }
        router.register("conversation.create") { params ->
            val agentId = param(params, "agent_id") ?: return@register adminError("agent_id required")
            api.post("agents", agentId, "conversations", body = params.toString())
        }
        router.register("conversation.delete") { params ->
            val id = param(params, "conversation_id") ?: return@register adminError("conversation_id required")
            api.delete("conversations", id)
        }
        router.register("conversation.archive") { params ->
            // Letta has no /conversations/{id}/archive|/unarchive sub-resource; archive
            // state is a field on the conversation, toggled via PATCH /v1/conversations/{id}
            // with {"archived": bool} (same route + response the HTTP client uses in
            // ConversationApi.updateConversation). That PATCH returns the updated
            // Conversation, which IrohAdminRpcConversationListSource decodes. Hitting a
            // phantom /archive sub-route would 404 and break iroh-mode archive/restore.
            val id = param(params, "conversation_id") ?: return@register adminError("conversation_id required")
            api.patch("conversations", id, body = """{"archived":true}""")
        }
        router.register("conversation.restore") { params ->
            val id = param(params, "conversation_id") ?: return@register adminError("conversation_id required")
            api.patch("conversations", id, body = """{"archived":false}""")
        }
        router.register("message.list") { params ->
            val convId = param(params, "conversation_id") ?: return@register adminError("conversation_id required")
            val response = api.get(
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
            MessageListWireProjection.projectMessageList(response, convId)
        }
        router.register("message.get") { params ->
            val convId = param(params, "conversation_id") ?: return@register adminError("conversation_id required")
            val msgId = param(params, "message_id") ?: return@register adminError("message_id required")
            api.get("conversations", convId, "messages", msgId)
        }
        router.register("tool_return.get") { params ->
            /**
             * letta-mobile-fe51r: on-demand full-body fetch for a projected
             * tool-return message. Returns the complete, unprojected message.
             */
            val convId = param(params, "conversation_id") ?: return@register adminError("conversation_id required")
            val msgId = param(params, "message_id") ?: return@register adminError("message_id required")
            api.get("conversations", convId, "messages", msgId)
        }
    }

    /**
     * letta-mobile-8vplf: handler-level parameter errors previously returned a
     * `{_error: ...}` object that the router wrapped in a `success: true`
     * envelope, so clients decoded an error as a successful result. Throwing
     * here routes the failure through [AdminRpcRouter.dispatch]'s catch path,
     * which encodes a proper `success: false` + `error` envelope. Other
     * handler files still carry private `{_error}` helpers — tracked by bead
     * letta-mobile-8vplf.
     */

}
