package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object ConversationAdminHandlers {
    /** Newest-window page size for single-message projection. */
    internal const val MESSAGE_GET_PAGE_LIMIT = 500

    /** Max pages walked oldest-ward for message.get / tool_return.get. */
    internal const val MESSAGE_GET_MAX_PAGES = 20

    /**
     * Wall-clock budget for the whole multi-page walk. Per-page NativeAdmin
     * attempts are still 2s each; without an overall budget a deep miss can
     * block for up to ~40s (20 × 2s).
     */
    internal const val MESSAGE_GET_BUDGET_MS = 8_000L

    /** Test-only page-size override (null = production [MESSAGE_GET_PAGE_LIMIT]). */
    @Volatile
    internal var messageGetPageLimitForTest: Int? = null

    /** Test-only budget override (null = production [MESSAGE_GET_BUDGET_MS]). */
    @Volatile
    internal var messageGetBudgetMsForTest: Long? = null

    private val messageGetPageLimit: Int
        get() = messageGetPageLimitForTest ?: MESSAGE_GET_PAGE_LIMIT

    private val messageGetBudgetMs: Long
        get() = messageGetBudgetMsForTest ?: MESSAGE_GET_BUDGET_MS

    fun register(
        router: AdminRpcRouter,
        tiers: NativeReadTiers = NativeReadTiers(),
        controller: com.letta.mobile.data.controller.AppServerController? = null,
    ) {
        val nativeClient = tiers.nativeClient
        registerConversationReadRoutes(router, nativeClient, tiers)
        registerConversationWriteRoutes(router, nativeClient, controller)
        registerMessageRoutes(router, nativeClient)
    }

    private fun registerConversationReadRoutes(
        router: AdminRpcRouter,
        nativeClient: AppServerClient?,
        tiers: NativeReadTiers,
    ) {
        router.registerScoped("conversation.list") { params, context ->
            val agentId = param(params, AdminParamKey("agent_id"))
            val conversations = NativeAdmin.require(nativeClient, NativeAdminOp.ConversationList) { c ->
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
            scopeConversationList(conversations, context)
        }
        // letta-mobile-i9h61.3.1: agent-scoped conversation list, served
        // from the shared local-backend store (the same data the
        // recipient's IrohAgentMessageRouter reads). Distinct from
        // conversation.list (which is the App Server v2 command and is
        // not agent-scoped in a way the client can rely on for
        // cross-agent routing). Fail-closed when the store is unset or
        // the agent doesn't exist.
        router.registerScoped("conversation.list_agent") { params, context ->
            val agentId = param(params, AdminParamKey("agent_id"))
                ?: return@registerScoped adminError("missing_required: agent_id")
            listAgentConversations(params, context, tiers, agentId)
        }
        router.registerScoped("conversation.get") { params, context ->
            val id = params.requireParam(AdminParamKey("conversation_id"))
            requireConversationAccess(context, id)
            NativeAdmin.require(nativeClient, NativeAdminOp.ConversationGet) { c ->
                val response = c.conversationRetrieve(
                    AppServerCommand.ConversationRetrieve(requestId = NativeAdmin.requestId(), conversationId = id),
                )
                if (response.success) response.conversation else null
            }
        }
    }

    /**
     * P3.4 (gn7kr.23): restricts a conversation-list result to the peer's authorized
     * conversation scope.
     */
    private fun scopeConversationList(result: JsonElement, context: AdminRpcRequestContext): JsonElement {
        if (result !is JsonArray) {
            Telemetry.event(
                "IrohNode", "conversation_list.non_array_shape",
                "kind" to if (result is JsonObject) "object" else "other",
                "keyCount" to ((result as? JsonObject)?.size ?: 0),
                "keys" to ((result as? JsonObject)?.keys.orEmpty().sorted().take(12).joinToString(",")),
                level = Telemetry.Level.WARN,
            )
        }
        val authorized = context.authorizedConversationIds ?: return result
        if (result !is JsonArray) return result
        val filtered = result.filter { element ->
            val id = (element as? JsonObject)?.get("id")?.let { it as? JsonPrimitive }?.contentOrNull
            id != null && id in authorized
        }
        return JsonArray(filtered)
    }

    private fun registerConversationWriteRoutes(
        router: AdminRpcRouter,
        nativeClient: AppServerClient?,
        controller: com.letta.mobile.data.controller.AppServerController?,
    ) {
        router.register("conversation.create") { params ->
            params.requireParam(AdminParamKey("agent_id"))
            val createBody = checkNotNull(params)
            NativeAdmin.require(nativeClient, NativeAdminOp.ConversationCreate) { c ->
                val response = c.conversationCreate(
                    AppServerCommand.ConversationCreate(requestId = NativeAdmin.requestId(), body = createBody),
                )
                if (response.success) response.conversation else null
            }
        }
        router.register("conversation.delete") { params ->
            params.requireParam(AdminParamKey("conversation_id"))
            // Phase 2: fail closed unconditionally. Prefer conversation.archive.
            adminError("capability_unavailable: conversation_delete is not in the pinned App Server v2 contract; archive instead")
        }
        router.register("conversation.update") { params ->
            val id = params.requireParam(AdminParamKey("conversation_id"))
            val body = kotlinx.serialization.json.buildJsonObject {
                params?.forEach { (key, value) ->
                    if (key != "conversation_id") put(key, value)
                }
            }
            val result = NativeAdmin.require(nativeClient, NativeAdminOp.ConversationUpdate) { c ->
                val response = c.conversationUpdate(
                    AppServerCommand.ConversationUpdate(
                        requestId = NativeAdmin.requestId(),
                        conversationId = id,
                        body = body,
                    ),
                )
                if (response.success) response.conversation else null
            }
            if (RuntimeInvalidationPolicy.conversationUpdateRequiresRestart(body)) {
                val agentId = param(params, AdminParamKey("agent_id"))
                    ?: (result as? JsonObject)?.get("agent_id")?.jsonPrimitive?.contentOrNull
                if (agentId != null) {
                    controller?.stopRuntime(com.letta.mobile.data.model.AgentId(agentId))
                }
            }
            result
        }
        router.register("conversation.archive") { params ->
            val id = params.requireParam(AdminParamKey("conversation_id"))
            NativeAdmin.require(nativeClient, NativeAdminOp.ConversationArchive) { c ->
                val response = c.conversationUpdate(
                    AppServerCommand.ConversationUpdate(
                        requestId = NativeAdmin.requestId(),
                        conversationId = id,
                        body = kotlinx.serialization.json.buildJsonObject {
                            put("archived", JsonPrimitive(true))
                        },
                    ),
                )
                if (response.success) response.conversation else null
            }
        }
        router.register("conversation.restore") { params ->
            val id = params.requireParam(AdminParamKey("conversation_id"))
            NativeAdmin.require(nativeClient, NativeAdminOp.ConversationRestore) { c ->
                val response = c.conversationUpdate(
                    AppServerCommand.ConversationUpdate(
                        requestId = NativeAdmin.requestId(),
                        conversationId = id,
                        body = kotlinx.serialization.json.buildJsonObject {
                            put("archived", JsonPrimitive(false))
                        },
                    ),
                )
                if (response.success) response.conversation else null
            }
        }
    }

    private fun registerMessageRoutes(
        router: AdminRpcRouter,
        nativeClient: AppServerClient?,
    ) {
        router.registerScoped("message.list") { params, context ->
            val convId = params.requireParam(AdminParamKey("conversation_id"))
            requireConversationAccess(context, convId)
            val effectiveLimit = param(params, AdminParamKey("limit")) ?: MessageListPageGuard.DEFAULT_PAGE_LIMIT.toString()
            val response = NativeAdmin.require(nativeClient, NativeAdminOp.MessageList) { c ->
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
            MessageListPageGuard.bound(
                MessageListWireProjection.projectMessageList(response, convId),
            )
        }
        router.registerScoped("message.get") { params, context ->
            val convId = params.requireParam(AdminParamKey("conversation_id"))
            requireConversationAccess(context, convId)
            val msgId = params.requireParam(AdminParamKey("message_id"))
            retrieveMessageNative(nativeClient, convId, msgId, op = NativeAdminOp.MessageGet)
        }
        router.registerScoped("tool_return.get") { params, context ->
            val convId = params.requireParam(AdminParamKey("conversation_id"))
            requireConversationAccess(context, convId)
            val msgId = params.requireParam(AdminParamKey("message_id"))
            retrieveMessageNative(nativeClient, convId, msgId, op = NativeAdminOp.ToolReturnGet)
        }
    }

    /**
     * Phase 2: project a single message from conversation_messages_list.
     * Walks newest-first pages (up to [MESSAGE_GET_MAX_PAGES] × [MESSAGE_GET_PAGE_LIMIT])
     * via the `before` cursor; missing ids fail closed (no shim).
     *
     * Each page uses its own [NativeAdmin.require] timeout, and the whole walk
     * is capped by [MESSAGE_GET_BUDGET_MS] so deep misses cannot block ~40s.
     */
    private suspend fun retrieveMessageNative(
        nativeClient: AppServerClient?,
        conversationId: String,
        messageId: String,
        op: NativeAdminOp,
    ): JsonElement {
        return try {
            kotlinx.coroutines.withTimeout(messageGetBudgetMs) {
                walkMessagePages(nativeClient, conversationId, messageId, op)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            adminError(
                "deadline_exceeded: message $messageId lookup exceeded searchable window budget",
            )
        }
    }

    private suspend fun walkMessagePages(
        nativeClient: AppServerClient?,
        conversationId: String,
        messageId: String,
        op: NativeAdminOp,
    ): JsonElement {
        var before: String? = null
        val pageLimit = messageGetPageLimit
        repeat(MESSAGE_GET_MAX_PAGES) {
            val messages = NativeAdmin.require(nativeClient, op) { c ->
                val native = c.conversationMessagesList(
                    AppServerCommand.ConversationMessagesList(
                        requestId = NativeAdmin.requestId(),
                        conversationId = conversationId,
                        query = NativeAdmin.queryOf(
                            "limit" to pageLimit.toString(),
                            "order" to "desc",
                            "before" to before,
                        ),
                    ),
                )
                if (!native.success) null else native.messages as? JsonArray
            }
            if (messages.isEmpty()) {
                adminError("not_found: message $messageId not in conversation history")
            }
            messages.firstOrNull { element ->
                (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull == messageId
            }?.let { return it }
            val oldestId = messageIdOf(messages.lastOrNull())
            if (shouldStopPaging(oldestId, before, messages.size, pageLimit)) {
                adminError("not_found: message $messageId not in conversation history")
            }
            before = oldestId
        }
        adminError("not_found: message $messageId not in searchable conversation window")
    }

    private fun messageIdOf(element: JsonElement?): String? =
        (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull

    private fun shouldStopPaging(
        oldestId: String?,
        previousBefore: String?,
        pageSize: Int,
        pageLimit: Int,
    ): Boolean = oldestId == null || oldestId == previousBefore || pageSize < pageLimit

    private fun requireConversationAccess(context: AdminRpcRequestContext, conversationId: String) {
        if (!context.canAccessConversation(conversationId)) {
            adminError("forbidden: conversation out of authorized scope")
        }
    }

    /**
     * letta-mobile-i9h61.3.1: serve `conversation.list_agent` from the
     * shared local-backend store. The store is the same one the
     * recipient's IrohAgentMessageRouter reads at receive time, so the
     * client picker can run the shared policy on the data the router
     * chose against. Returns a JsonArray of conversation objects in the
     * same wire shape the App Server v2 conversation.list returns, so
     * the client decode path is identical.
     *
     * Fail-closed when the store is unset or the agent is unknown —
     * registered via CapabilityUnavailable at assembly time so the
     * method is gated by store availability, not silently returning []
     * (which would be indistinguishable from "agent has no conversations"
     * and lets the picker fall through to a wrong / fresh conversation).
     */
    private fun listAgentConversations(
        params: JsonObject?,
        context: com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext,
        tiers: NativeReadTiers,
        agentId: String,
    ): JsonElement {
        val store = tiers.localBackendStore
            ?: return adminError("capability_unavailable: conversation.list_agent requires the local backend store")
        if (!store.agentExists(agentId)) {
            Telemetry.event(
                "IrohNode", "conversation.list_agent.agent_missing",
                "agentId" to agentId,
                level = Telemetry.Level.WARN,
            )
            return JsonArray(emptyList())
        }
        val limit = (param(params, AdminParamKey("limit"))?.toIntOrNull() ?: 200)
            .coerceIn(1, MAX_AGENT_LIST_FETCH)
        val archiveStatus = param(params, AdminParamKey("archive_status")) ?: "active"
        val rows = store.listConversationsProjected(
            agentId = agentId,
            archiveStatus = archiveStatus,
            limit = limit,
            offset = 0,
        ) ?: return adminError("local_backend_error: listConversationsProjected returned null")
        val decoded = rows.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            decodeConversationObject(obj, agentId)
        }
        return scopeConversationList(JsonArray(decoded), context)
    }

    /**
     * letta-mobile-i9h61.3.1: shape-parity decode for the
     * conversation objects the local-backend store returns from
     * [LocalBackendAdminStore.listConversationsProjected]. Mirrors
     * `A2aWiring.decodeConversation` (iroh-wrapper-cli) — kept as a
     * private copy because the admin handler must be self-contained
     * (A2aWiring.decodeConversation is internal to the wrapper module).
     * If the wrapper's shape diverges, both must be updated together.
     */
    private fun decodeConversationObject(obj: JsonObject, fallbackAgentId: String): JsonObject {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return obj
        val agentId = obj["agent_id"]?.jsonPrimitive?.contentOrNull ?: fallbackAgentId
        val updated = obj.toMutableMap()
        updated["id"] = JsonPrimitive(id)
        updated["agent_id"] = JsonPrimitive(agentId)
        return JsonObject(updated)
    }

    /** Hard cap on per-call conversation fetch — the recipient's router reads all active
     *  conversations at receive time, so the client picker doesn't need a full pagination
     *  scheme; we fetch once with this cap and let the picker pick from the result. */
    private const val MAX_AGENT_LIST_FETCH = 500
}
