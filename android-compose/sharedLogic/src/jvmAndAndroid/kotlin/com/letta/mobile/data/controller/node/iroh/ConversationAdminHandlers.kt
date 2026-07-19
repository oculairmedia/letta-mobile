package com.letta.mobile.data.controller.node.iroh

object ConversationAdminHandlers {
    fun register(router: AdminRpcRouter, adminBaseUrl: String) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        router.register("conversation.list") { params ->
            val agentId = param(params, "agent_id")
            val path = if (agentId != null) {
                AdminPath.v1("agents", agentId, "conversations")
            } else {
                AdminPath.v1("conversations")
            }
            api.get(path) {
                query("limit", param(params, "limit"))
                query("after", param(params, "after"))
                query("archive_status", param(params, "archive_status"))
                query("summary_search", param(params, "summary_search"))
                query("order", param(params, "order"))
                query("order_by", param(params, "order_by"))
            }
        }
        router.register("conversation.get") { params ->
            val id = params.requireParam("conversation_id")
            api.get(AdminPath.v1("conversations", id))
        }
        router.register("conversation.create") { params ->
            // Current App Server exposes the canonical create route at
            // POST /v1/conversations with agent_id in the JSON body. The
            // legacy agent-scoped route is not registered and returns 404.
            params.requireParam("agent_id")
            api.post(AdminPath.v1("conversations"), body = params.toString())
        }
        router.register("conversation.delete") { params ->
            val id = params.requireParam("conversation_id")
            api.delete(AdminPath.v1("conversations", id))
        }
        router.register("conversation.archive") { params ->
            // Letta has no /conversations/{id}/archive|/unarchive sub-resource; archive
            // state is a field on the conversation, toggled via PATCH /v1/conversations/{id}
            // with {"archived": bool} (same route + response the HTTP client uses in
            // ConversationApi.updateConversation). That PATCH returns the updated
            // Conversation, which IrohAdminRpcConversationListSource decodes. Hitting a
            // phantom /archive sub-route would 404 and break iroh-mode archive/restore.
            val id = params.requireParam("conversation_id")
            api.patch(AdminPath.v1("conversations", id), body = """{"archived":true}""")
        }
        router.register("conversation.restore") { params ->
            val id = params.requireParam("conversation_id")
            api.patch(AdminPath.v1("conversations", id), body = """{"archived":false}""")
        }
        router.register("message.list") { params ->
            val convId = params.requireParam("conversation_id")
            // letta-mobile-c4igq.9: enforce a bounded newest-window even when the
            // client sends no limit. An unbounded message.list on a ~60MB transcript
            // built one giant admin_rpc response the frame layer rejects
            // (response_too_large). Default to MessageListPageGuard.DEFAULT_PAGE_LIMIT
            // so hydration always requests a bounded window; the guard below then
            // shrinks-not-rejects if a page is still oversized.
            val effectiveLimit = param(params, "limit") ?: MessageListPageGuard.DEFAULT_PAGE_LIMIT.toString()
            val response = api.get(
                AdminPath.v1("conversations", convId, "messages").builder()
                    .query("limit", effectiveLimit)
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
            // letta-mobile-c4igq.9: final page-size guard — never emit a response
            // over the frame cap. If the projected page is still too large, trim
            // to the newest rows that fit + a continuation cursor (has_more,
            // next_before) the existing -cursor pager can follow.
            MessageListPageGuard.bound(
                MessageListWireProjection.projectMessageList(response, convId),
            )
        }
        router.register("message.get") { params ->
            val convId = params.requireParam("conversation_id")
            val msgId = params.requireParam("message_id")
            api.get(AdminPath.v1("conversations", convId, "messages", msgId))
        }
        router.register("tool_return.get") { params ->
            /**
             * letta-mobile-fe51r: on-demand full-body fetch for a projected
             * tool-return message. Returns the complete, unprojected message.
             */
            val convId = params.requireParam("conversation_id")
            val msgId = params.requireParam("message_id")
            api.get(AdminPath.v1("conversations", convId, "messages", msgId))
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
