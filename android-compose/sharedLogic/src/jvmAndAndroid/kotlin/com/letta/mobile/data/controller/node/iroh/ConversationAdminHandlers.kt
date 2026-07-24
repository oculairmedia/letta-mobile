package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray

object ConversationAdminHandlers {
    fun register(
        router: AdminRpcRouter,
        adminBaseUrl: String,
        nativeClient: AppServerClient? = null,
        shimRetired: Boolean = false,
        // lgns8.9: on-disk backend store; when set, conversation.list serves from
        // disk (native tier) ahead of the shim proxy. Null = disabled = proxy only.
        localStore: LocalBackendAdminStore? = null,
    ) {
        val api = AdminHandlerSupport(AdminProxyClient(adminBaseUrl))
        registerConversationReadRoutes(router, api, nativeClient, localStore)
        registerConversationWriteRoutes(router, api, nativeClient, shimRetired)
        registerMessageRoutes(router, api, nativeClient, localStore)
    }

    private fun registerConversationReadRoutes(
        router: AdminRpcRouter,
        api: AdminHandlerSupport,
        nativeClient: AppServerClient?,
        localStore: LocalBackendAdminStore?,
    ) {
        router.register("conversation.list") { params ->
            val agentId = param(params, AdminParamKey("agent_id"))
            NativeAdmin.attempt(nativeClient, "conversation.list") { c ->
                val response = c.conversationList(
                    AppServerCommand.ConversationList(
                        requestId = NativeAdmin.requestId(),
                        query = NativeAdmin.queryOf(
                            "agent_id" to agentId,
                            "limit" to param(params, AdminParamKey("limit")),
                            "after" to param(params, AdminParamKey("after")),
                            "archive_status" to param(params, AdminParamKey("archive_status")),
                            "summary_search" to param(params, AdminParamKey("summary_search")),
                            "order" to param(params, AdminParamKey("order")),
                            "order_by" to param(params, AdminParamKey("order_by")),
                        ),
                    ),
                )
                if (response.success) response.conversations ?: JsonArray(emptyList()) else null
            }
                // lgns8.9 native-store tier: serve from disk (null on any error),
                // else fall through to the shim proxy. Verified to match the shim's
                // withRealTimes ordering byte-for-byte on the live store.
                ?: localStore?.listConversationsProjected(
                    agentId = agentId,
                    archiveStatus = param(params, AdminParamKey("archive_status")),
                    limit = param(params, AdminParamKey("limit"))?.toIntOrNull(),
                    offset = param(params, AdminParamKey("offset"))?.toIntOrNull(),
                )
                ?: run {
                // #962: the App Server only serves the flat GET /v1/conversations
                // route, filtering by an agent_id query param; the agent-scoped
                // /v1/agents/{id}/conversations route is not registered and 404s.
                api.get(AdminPath.v1("conversations")) {
                    query("agent_id", agentId)
                    query("limit", param(params, AdminParamKey("limit")))
                    query("after", param(params, AdminParamKey("after")))
                    query("archive_status", param(params, AdminParamKey("archive_status")))
                    query("summary_search", param(params, AdminParamKey("summary_search")))
                    query("order", param(params, AdminParamKey("order")))
                    query("order_by", param(params, AdminParamKey("order_by")))
                }
            }
        }
        router.register("conversation.get") { params ->
            val id = params.requireParam(AdminParamKey("conversation_id"))
            NativeAdmin.attempt(nativeClient, "conversation.get") { c ->
                val response = c.conversationRetrieve(
                    AppServerCommand.ConversationRetrieve(requestId = NativeAdmin.requestId(), conversationId = id),
                )
                if (response.success) response.conversation else null
            } ?: api.get(AdminPath.v1("conversations", id))
        }
    }

    private fun registerConversationWriteRoutes(
        router: AdminRpcRouter,
        api: AdminHandlerSupport,
        nativeClient: AppServerClient?,
        shimRetired: Boolean,
    ) {
        router.register("conversation.create") { params ->
            // Current App Server exposes the canonical create route at
            // POST /v1/conversations with agent_id in the JSON body. The
            // legacy agent-scoped route is not registered and returns 404.
            params.requireParam(AdminParamKey("agent_id"))
            val createBody = checkNotNull(params)
            NativeAdmin.attempt(nativeClient, "conversation.create") { c ->
                val response = c.conversationCreate(
                    AppServerCommand.ConversationCreate(requestId = NativeAdmin.requestId(), body = createBody),
                )
                if (response.success) response.conversation else null
            } ?: api.post(AdminPath.v1("conversations"), body = params.toString())
        }
        router.register("conversation.delete") { params ->
            params.requireParam(AdminParamKey("conversation_id"))
            // lgns8.8 (matrix: capability_gated_unsupported, deny_fail_closed):
            // conversation_delete is absent from the pinned v2 inventory. Once
            // the shim is retired there is no backend for it — return a typed
            // capability denial instead of pretending. Until cutover the shim
            // DELETE keeps product behavior.
            if (shimRetired) {
                adminError("capability_unavailable: conversation_delete is not in the pinned App Server v2 contract; archive instead")
            }
            val id = params.requireParam(AdminParamKey("conversation_id"))
            api.delete(AdminPath.v1("conversations", id))
        }
        router.register("conversation.update") { params ->
            val id = params.requireParam(AdminParamKey("conversation_id"))
            val body = kotlinx.serialization.json.buildJsonObject {
                params?.forEach { (key, value) ->
                    if (key != "conversation_id") put(key, value)
                }
            }
            NativeAdmin.attempt(nativeClient, "conversation.update") { c ->
                val response = c.conversationUpdate(
                    AppServerCommand.ConversationUpdate(
                        requestId = NativeAdmin.requestId(),
                        conversationId = id,
                        body = body,
                    ),
                )
                if (response.success) response.conversation else null
            } ?: api.patch(AdminPath.v1("conversations", id), body = body.toString())
        }
        router.register("conversation.archive") { params ->
            // Letta has no /conversations/{id}/archive|/unarchive sub-resource; archive
            // state is a field on the conversation, toggled via PATCH /v1/conversations/{id}
            // with {"archived": bool} (same route + response the HTTP client uses in
            // ConversationApi.updateConversation). That PATCH returns the updated
            // Conversation, which IrohAdminRpcConversationListSource decodes. Hitting a
            // phantom /archive sub-route would 404 and break iroh-mode archive/restore.
            val id = params.requireParam(AdminParamKey("conversation_id"))
            NativeAdmin.attempt(nativeClient, "conversation.archive") { c ->
                val response = c.conversationUpdate(
                    AppServerCommand.ConversationUpdate(
                        requestId = NativeAdmin.requestId(),
                        conversationId = id,
                        body = kotlinx.serialization.json.buildJsonObject { put("archived", kotlinx.serialization.json.JsonPrimitive(true)) },
                    ),
                )
                if (response.success) response.conversation else null
            } ?: api.patch(AdminPath.v1("conversations", id), body = """{"archived":true}""")
        }
        router.register("conversation.restore") { params ->
            val id = params.requireParam(AdminParamKey("conversation_id"))
            NativeAdmin.attempt(nativeClient, "conversation.restore") { c ->
                val response = c.conversationUpdate(
                    AppServerCommand.ConversationUpdate(
                        requestId = NativeAdmin.requestId(),
                        conversationId = id,
                        body = kotlinx.serialization.json.buildJsonObject { put("archived", kotlinx.serialization.json.JsonPrimitive(false)) },
                    ),
                )
                if (response.success) response.conversation else null
            } ?: api.patch(AdminPath.v1("conversations", id), body = """{"archived":false}""")
        }
    }

    private fun registerMessageRoutes(
        router: AdminRpcRouter,
        api: AdminHandlerSupport,
        nativeClient: AppServerClient?,
        // lgns8.9 slice 3: on-disk backend store; when set, message.list serves
        // already-projected wire messages from disk ahead of the shim proxy.
        localStore: LocalBackendAdminStore? = null,
    ) {
        router.registerScoped("message.list") { params, context ->
            val convId = params.requireParam(AdminParamKey("conversation_id"))
            requireConversationAccess(context, convId)
            // letta-mobile-c4igq.9: enforce a bounded newest-window even when the
            // client sends no limit. An unbounded message.list on a ~60MB transcript
            // built one giant admin_rpc response the frame layer rejects
            // (response_too_large). Default to MessageListPageGuard.DEFAULT_PAGE_LIMIT
            // so hydration always requests a bounded window; the guard below then
            // shrinks-not-rejects if a page is still oversized.
            val effectiveLimit = param(params, AdminParamKey("limit")) ?: MessageListPageGuard.DEFAULT_PAGE_LIMIT.toString()
            val response = NativeAdmin.attempt(nativeClient, "message.list") { c ->
                val native = c.conversationMessagesList(
                    AppServerCommand.ConversationMessagesList(
                        requestId = NativeAdmin.requestId(),
                        conversationId = convId,
                        query = NativeAdmin.queryOf(
                            "limit" to effectiveLimit,
                            "after" to param(params, AdminParamKey("after")),
                            "before" to param(params, AdminParamKey("before")),
                            "order" to param(params, AdminParamKey("order")),
                        ),
                    ),
                )
                if (native.success) native.messages else null
            }
            // lgns8.9 slice 3 native-store tier: serve already-projected wire
            // messages from disk (null on any error → fall through to the shim
            // proxy). Mirrors the shim /messages route (limit/before/order;
            // `after` and in-flight filtering intentionally omitted — see
            // LocalBackendAdminStore.listMessagesProjected).
                ?: localStore?.listMessagesProjected(
                    conversationId = convId,
                    agentId = param(params, AdminParamKey("agent_id")),
                    limit = effectiveLimit.toIntOrNull(),
                    before = param(params, AdminParamKey("before")),
                    after = param(params, AdminParamKey("after")),
                    order = param(params, AdminParamKey("order")),
                )
                ?: api.get(
                AdminPath.v1("conversations", convId, "messages").builder()
                    .query("limit", effectiveLimit)
                    .query("after", param(params, AdminParamKey("after")))
                    // letta-mobile-71orq: backward pagination (scroll up for
                    // history) cursors on `before`; the raw HTTP path
                    // (MessageApi.fetchRecentMessages) hits the same endpoint
                    // with a `before` query param, so pass it through so
                    // iroh:// older-message loads mirror HTTP.
                    .query("before", param(params, AdminParamKey("before")))
                    .query("order", param(params, AdminParamKey("order")))
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
        router.registerScoped("message.get") { params, context ->
            val convId = params.requireParam(AdminParamKey("conversation_id"))
            requireConversationAccess(context, convId)
            val msgId = params.requireParam(AdminParamKey("message_id"))
            api.get(AdminPath.v1("conversations", convId, "messages", msgId))
        }
        router.registerScoped("tool_return.get") { params, context ->
            /**
             * letta-mobile-fe51r: on-demand full-body fetch for a projected
             * tool-return message. Returns the complete, unprojected message.
             */
            val convId = params.requireParam(AdminParamKey("conversation_id"))
            requireConversationAccess(context, convId)
            val msgId = params.requireParam(AdminParamKey("message_id"))
            api.get(AdminPath.v1("conversations", convId, "messages", msgId))
        }
    }

    /**
     * lgns8.12: conversation-content reads reject cross-scope access BEFORE
     * any proxy call. Uniform denial without leaking whether the target
     * conversation exists.
     */
    private fun requireConversationAccess(context: AdminRpcRequestContext, conversationId: String) {
        if (!context.canAccessConversation(conversationId)) {
            adminError("forbidden: conversation out of authorized scope")
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
