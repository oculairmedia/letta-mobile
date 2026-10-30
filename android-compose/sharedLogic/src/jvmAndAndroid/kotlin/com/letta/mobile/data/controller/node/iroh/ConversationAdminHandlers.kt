package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.attr
import com.letta.mobile.data.controller.node.listcache.HeldListCache
import com.letta.mobile.data.controller.node.listcache.HeldListLimits
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.resolveConversationReadTarget
import com.letta.mobile.data.chat.branch.IrohConversationForkRpc
import com.letta.mobile.data.runtime.TurnIdentityLedger
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.util.Telemetry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

/** A successful conversation write: its result, the id when the result has none, its kind, the agent id when the result has none. */
private typealias ConversationWriteNotice = suspend (JsonElement?, String?, ConversationChangeKind, String?) -> Unit

object ConversationAdminHandlers {
    /**
     * Newest-window page size for single-message projection.
     *
     * walkMessagePages asks the real App Server for this
     * many RAW, unprojected messages per page over the wrapper's own WS link
     * to it (AppServerWebSocketLimits.MAX_FRAME_BYTES = 16 MiB, no chunking).
     * message.list's size guards (MessageListPageGuard, MessageListWireProjection)
     * only run on what the wrapper serves onward — they can't protect this
     * inbound hop. A page of large tool-return bodies can itself exceed 16 MiB
     * in one WS frame from the App Server, which kills that ENTIRE shared
     * connection (not just this request) until it reconnects — every other
     * admin_rpc route sharing it (agent_list, conversation_list, list_models,
     * running turns) breaks simultaneously. Worse, message.get always walks
     * from the newest page, so a message living on an oversized page kills the
     * connection again on every future lookup of it — not a one-off blip.
     * 500 was excessive for what's just a "find one message by id" scan;
     * shrinking it lowers the odds any single page crosses that ceiling.
     */
    internal const val MESSAGE_GET_PAGE_LIMIT = 100

    /** Preserve the pre-page-shrink searchable window while keeping each frame small. */
    internal const val MESSAGE_GET_MAX_SEARCHABLE_MESSAGES = 10_000

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

    private fun messageGetPageLimit(): Int = messageGetPageLimitForTest ?: MESSAGE_GET_PAGE_LIMIT

    private fun messageGetBudgetMs(): Long = messageGetBudgetMsForTest ?: MESSAGE_GET_BUDGET_MS

    fun register(
        router: AdminRpcRouter,
        tiers: NativeReadTiers = NativeReadTiers(),
        controller: com.letta.mobile.data.controller.AppServerController? = null,
    ) {
        val nativeClient = tiers.nativeClient
        // Without a local store there is no cheap change signal, so every list is fetched as before.
        val heldList = tiers.localBackendStore?.let { store ->
            HeldListCache(
                signature = store::conversationRecordsSignature,
                limits = HeldListLimits(maxItems = MAX_HELD_CONVERSATIONS),
                onTooLarge = { conversations, max -> reportHeldListTooLarge("conversation.list", conversations, max) },
            )
        }
        val onChanged: suspend (String, String?, ConversationChangeKind) -> Unit = { id, agent, kind ->
            heldList?.invalidate()
            tiers.conversationChanges?.notify(id, agent, kind)
        }
        registerConversationReadRoutes(router, nativeClient, tiers, heldList)
        registerConversationWriteRoutes(router, nativeClient, controller, onChanged)
        registerMessageRoutes(router, nativeClient, tiers.localBackendStore, tiers.turnIdentity)
    }

    private fun registerConversationReadRoutes(
        router: AdminRpcRouter,
        nativeClient: AppServerClient?,
        tiers: NativeReadTiers,
        heldList: HeldListCache?,
    ) {
        val hiddenAgents = tiers.localBackendStore?.let { store -> HiddenAgentIndex(load = store::hiddenAgentIds) }
        registerConversationListRoute(router, nativeClient, hiddenAgents, heldList)
        registerConversationAgentListRoute(router, tiers)
        registerConversationGetRoute(router, nativeClient)
    }

    /**
     * `visible_only: true` opts unfiltered, non-search requests into visible recents (default 30).
     * Agent-free, hidden, archived and unauthorized rows are removed before cursor and limit.
     * Requires the local agent visibility index; without it the opt-in request fails closed.
     * Other requests retain the App Server contract. Recents share the per-agent held full list.
     */
    private fun registerConversationListRoute(
        router: AdminRpcRouter,
        nativeClient: AppServerClient?,
        hiddenAgents: HiddenAgentIndex?,
        heldList: HeldListCache?,
    ) {
        router.registerScoped("conversation.list") { params, context ->
            val agentId = param(params, AdminParamKey("agent_id"))
            val conversations = NativeAdmin.require(nativeClient, NativeAdminOp.ConversationList) { c ->
                if (wantsVisibleConversationPage(params)) {
                    return@require hiddenAgents.visibleRecents(c, params, context, heldList)
                }
                val own = suspend { listConversations(c, params) }
                // One agent's page (no summary search) is cut from the held full list: the App Server
                // re-reads every conversation record per list call, so per-agent calls cost the same as
                // a full one and a client listing agent by agent paid that once per agent.
                // An empty or all-digit agent_id makes the App Server drop the filter, so those pass through.
                val sliceable = !agentId.isNullOrEmpty() && agentId.toLongOrNull() == null &&
                    param(params, AdminParamKey("summary_search")) == null
                val held = heldList?.takeIf { sliceable }
                if (held == null) own() else held.serve(
                    fetchAll = { listAgentConversations(c) },
                    fromFull = { full ->
                        agentConversationPage(
                            full,
                            agent = AgentId(checkNotNull(agentId)),
                            after = param(params, AdminParamKey("after"))?.let(::ConversationId),
                            limit = param(params, AdminParamKey("limit")),
                        )
                    },
                    fetchOwn = own,
                )
            }
            val scoped = scopeConversationList(conversations, context)
            // letta-mobile-fxoew.9: the agent-wide chat list drops conversations owned by hidden
            // (subagent) agents. A list asked for one agent explicitly is served as is.
            if (agentId == null && hiddenAgents != null) dropHiddenAgentConversations(scoped, hiddenAgents) else scoped
        }
    }

    /**
     * The visible recents page (see [registerConversationListRoute]). Above the holding cap, or
     * without a usable signature, the full list is fetched rather than filtering a truncated raw
     * window, so a page is never underfilled.
     */
    private suspend fun HiddenAgentIndex?.visibleRecents(
        client: AppServerClient,
        params: JsonObject?,
        context: AdminRpcRequestContext,
        heldList: HeldListCache?,
    ): JsonArray? {
        val visibility = this ?: adminError("capability_unavailable: visible_only requires local agent visibility")
        val request = param(params, AdminParamKey("after"))?.let(::ConversationId) to param(params, AdminParamKey("limit"))
        val page: (JsonArray) -> JsonArray = { full ->
            val hidden = visibility.verifiedHiddenAgentIds()
                ?: adminError("capability_unavailable: visible_only could not read agent visibility")
            visibleConversationPage(full, hidden, context, request)
        }
        // Never ask for more than the holding cap in one response: an unbounded list can exceed the
        // App Server frame limit and close the shared session. A longer list fails explicitly.
        val ownVisible = suspend {
            listAgentConversations(client)?.also { full ->
                if (full.size > MAX_HELD_CONVERSATIONS) {
                    adminError("capability_unavailable: visible_only supports at most $MAX_HELD_CONVERSATIONS conversations")
                }
            }?.let(page)
        }
        if (heldList == null) return ownVisible()
        return heldList.serve(fetchAll = { listAgentConversations(client) }, fromFull = page, fetchOwn = ownVisible)
    }

    /** The conversation list exactly as the client asked for it, from the App Server. */
    private suspend fun listConversations(client: AppServerClient, params: JsonObject?): JsonArray? {
        val response = client.conversationList(
            AppServerCommand.ConversationList(
                requestId = NativeAdmin.requestId(),
                query = NativeAdmin.queryOf(
                    "agent_id" to param(params, AdminParamKey("agent_id")),
                    "limit" to param(params, AdminParamKey("limit")),
                    "after" to param(params, AdminParamKey("after")),
                    "archive_status" to param(params, AdminParamKey("archive_status")),
                    "summary_search" to param(params, AdminParamKey("summary_search")),
                    "order" to param(params, AdminParamKey("order")),
                    "order_by" to param(params, AdminParamKey("order_by")),
                ),
            ),
        )
        return if (response.success) response.conversations ?: JsonArray(emptyList()) else null
    }

    /**
     * Every visible conversation, newest first: the App Server's full list, asking for one more row
     * than is held so a list too long to hold is seen as such. Agent-free rows stay in, so the size
     * is the App Server's; no agent's page includes them.
     */
    private suspend fun listAgentConversations(client: AppServerClient): JsonArray? {
        val response = client.conversationList(
            AppServerCommand.ConversationList(
                requestId = NativeAdmin.requestId(),
                query = NativeAdmin.queryOf("limit" to (MAX_HELD_CONVERSATIONS + 1).toString()),
            ),
        )
        return if (response.success) response.conversations ?: JsonArray(emptyList()) else null
    }

    private fun dropHiddenAgentConversations(result: JsonElement, hiddenAgents: HiddenAgentIndex): JsonElement {
        val rows = result as? JsonArray ?: return result
        val visible = withoutHiddenAgentConversations(rows, hiddenAgents.hiddenAgentIds())
        if (visible.size != rows.size) {
            Telemetry.event("IrohNode", "conversation_list.hidden_agent_rows_dropped", "dropped" attr (rows.size - visible.size))
        }
        return JsonArray(visible)
    }

    private fun registerConversationAgentListRoute(router: AdminRpcRouter, tiers: NativeReadTiers) {
        router.registerScoped("conversation.list_agent") { params, context ->
            val agentId = param(params, AdminParamKey("agent_id"))
                ?: return@registerScoped adminError("missing_required: agent_id")
            val request = AgentListRequest(params, context, tiers, agentId)
            ConversationAgentListHelper.listAgentConversations(request, ::scopeConversationList)
        }
    }

    private fun registerConversationGetRoute(router: AdminRpcRouter, nativeClient: AppServerClient?) {
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
     * Restricts a conversation-list result to the peer's authorized
     * conversation scope.
     */
    private fun scopeConversationList(result: JsonElement, context: AdminRpcRequestContext): JsonElement {
        if (result !is JsonArray) {
            Telemetry.event(
                "IrohNode", "conversation_list.non_array_shape",
                "kind" attr if (result is JsonObject) "object" else "other",
                "keyCount" attr ((result as? JsonObject)?.size ?: 0),
                "keys" attr ((result as? JsonObject)?.keys.orEmpty().sorted().take(12).joinToString(",")),
                level = Telemetry.Level.WARN,
            )
        }
        if (result !is JsonArray) return result
        // letta-mobile-fxoew.6: lists never carry hidden (subagent) conversations. The App
        // Server's own list already drops them (`include_hidden` defaults off); this covers the
        // on-disk list_agent tier, which did not.
        val visible = result.filterNot { element -> (element as? JsonObject)?.isHiddenConversation() == true }
        val authorized = context.authorizedConversationIds ?: return JsonArray(visible)
        val filtered = visible.filter { element ->
            val id = (element as? JsonObject)?.get("id")?.let { it as? JsonPrimitive }?.contentOrNull
            id != null && id in authorized
        }
        return JsonArray(filtered)
    }

    private fun registerConversationWriteRoutes(
        router: AdminRpcRouter,
        nativeClient: AppServerClient?,
        controller: com.letta.mobile.data.controller.AppServerController?,
        onChanged: suspend (String, String?, ConversationChangeKind) -> Unit,
    ) {
        // Every conversation write from an Iroh client lands in these handlers, so this is where
        // connected clients learn of it (ConversationChangeNotifier, letta-mobile-lks7m) and the held
        // conversation list is dropped. Only a successful write notifies: a failed one tells no client
        // to refetch. Arguments: the write's result, the id when the result has none, the kind, and
        // the agent id when the result has none.
        val notifyChanged: ConversationWriteNotice = { result, fallbackId, kind, fallbackAgentId ->
            val conversation = result as? JsonObject
            val id = conversation?.get("id")?.jsonPrimitive?.contentOrNull ?: fallbackId
            if (id != null) {
                onChanged(id, conversation?.get("agent_id")?.jsonPrimitive?.contentOrNull ?: fallbackAgentId, kind)
            }
        }
        registerConversationCreateRoute(router, nativeClient, notifyChanged)
        router.register("conversation.delete") { params ->
            params.requireParam(AdminParamKey("conversation_id"))
            // Phase 2: fail closed unconditionally. Prefer conversation.archive.
            adminError("capability_unavailable: conversation_delete is not in the pinned App Server v2 contract; archive instead")
        }
        registerConversationUpdateRoute(router, nativeClient, controller, notifyChanged)
        registerConversationArchiveRoutes(router, nativeClient, notifyChanged)
        router.register(IrohConversationForkRpc.METHOD) { params ->
            val sourceId = params.requireParam(AdminParamKey(IrohConversationForkRpc.CONVERSATION_ID))
            // notifyChanged reads the fork's id from the result, so only a real fork notifies.
            ConversationForkHandler.fork(nativeClient, sourceId, params)
                .also { notifyChanged(it, null, ConversationChangeKind.Created, null) }
        }
    }

    private fun registerConversationCreateRoute(
        router: AdminRpcRouter,
        nativeClient: AppServerClient?,
        notifyChanged: ConversationWriteNotice,
    ) {
        router.register("conversation.create") { params ->
            val agentId = params.requireParam(AdminParamKey("agent_id"))
            val createBody = checkNotNull(params)
            NativeAdmin.require(nativeClient, NativeAdminOp.ConversationCreate) { c ->
                val response = c.conversationCreate(
                    AppServerCommand.ConversationCreate(requestId = NativeAdmin.requestId(), body = createBody),
                )
                if (response.success) response.conversation else null
            }.also { if (it is JsonObject) notifyChanged(it, null, ConversationChangeKind.Created, agentId) }
        }
    }

    private fun registerConversationUpdateRoute(
        router: AdminRpcRouter,
        nativeClient: AppServerClient?,
        controller: com.letta.mobile.data.controller.AppServerController?,
        notifyChanged: ConversationWriteNotice,
    ) {
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
            if (result is JsonObject) notifyChanged(result, id, ConversationChangeKind.Updated, null)
            if (RuntimeInvalidationPolicy.conversationUpdateRequiresRestart(body)) {
                val agentId = param(params, AdminParamKey("agent_id"))
                    ?: (result as? JsonObject)?.get("agent_id")?.jsonPrimitive?.contentOrNull
                if (agentId != null) {
                    controller?.stopRuntime(com.letta.mobile.data.model.AgentId(agentId))
                }
            }
            result
        }
    }

    /** `conversation.archive` and `conversation.restore`: the same update with the archived flag set or cleared. */
    private fun registerConversationArchiveRoutes(
        router: AdminRpcRouter,
        nativeClient: AppServerClient?,
        notifyChanged: ConversationWriteNotice,
    ) {
        for ((method, archived) in listOf("conversation.archive" to true, "conversation.restore" to false)) {
            val op = if (archived) NativeAdminOp.ConversationArchive else NativeAdminOp.ConversationRestore
            val kind = if (archived) ConversationChangeKind.Archived else ConversationChangeKind.Restored
            router.register(method) { params ->
                val id = params.requireParam(AdminParamKey("conversation_id"))
                NativeAdmin.require(nativeClient, op) { c ->
                    val response = c.conversationUpdate(
                        AppServerCommand.ConversationUpdate(
                            requestId = NativeAdmin.requestId(),
                            conversationId = id,
                            body = kotlinx.serialization.json.buildJsonObject {
                                put("archived", JsonPrimitive(archived))
                            },
                        ),
                    )
                    if (response.success) response.conversation else null
                }.also { if (it is JsonObject) notifyChanged(it, id, kind, null) }
            }
        }
    }

    private fun registerMessageRoutes(
        router: AdminRpcRouter,
        nativeClient: AppServerClient?,
        localStore: LocalBackendAdminStore?,
        turnIdentity: TurnIdentityLedger?,
    ) {
        router.registerScoped("message.list") { params, context ->
            val convId = params.requireParam(AdminParamKey("conversation_id"))
            requireConversationAccess(context, convId)
            val effectiveLimit = param(params, AdminParamKey("limit")) ?: MessageListPageGuard.DEFAULT_PAGE_LIMIT.toString()
            val response = NativeAdmin.require(nativeClient, NativeAdminOp.MessageList) { c ->
                val native = c.conversationMessagesList(
                    nativeMessageList(
                        ConversationId(convId),
                        "limit" to effectiveLimit,
                        "after" to param(params, AdminParamKey("after")),
                        "before" to param(params, AdminParamKey("before")),
                        "order" to param(params, AdminParamKey("order")),
                    ),
                )
                if (native.success) native.messages else null
            }
            val projected = MessageListWireProjection.projectMessageList(response, convId).let { wire ->
                // Keep App Server membership, ordering and text authoritative. Enrich only
                // exact user IDs already returned by this authorized page.
                val rows = wire as? JsonArray ?: return@let wire
                // Read from the transcript's end: a full parse cost grew with the conversation and
                // ran again after every append during a live turn.
                val local = localStore?.newestMessageContents(ConversationId(convId), ENRICHMENT_WINDOW)
                    ?.filterIsInstance<JsonObject>()?.associateBy { it["id"] } ?: return@let wire
                JsonArray(rows.map { element ->
                    val row = element as? JsonObject ?: return@map element
                    if (row["message_type"]?.jsonPrimitive?.content != "user_message") return@map row
                    val content = row["content"]
                    if (content is JsonArray && content.any { (it as? JsonObject)?.get("type")?.jsonPrimitive?.content == "image" }) return@map row
                    var imageBudget = 16_384
                    val images = (local[row["id"]]?.get("content") as? JsonArray)?.filter {
                        val image = it as? JsonObject
                        val source = image?.get("source") as? JsonObject
                        val size = source?.get("data")?.jsonPrimitive?.content?.length ?: 0
                        val accepted = image?.get("type")?.jsonPrimitive?.content == "image" && size in 1..imageBudget
                        if (accepted) imageBudget -= size
                        accepted
                    }.orEmpty()
                    if (images.isEmpty()) row else {
                        val textParts = if (content is JsonArray) content.toList() else listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to (content ?: JsonPrimitive("")))))
                        JsonObject(row + ("content" to JsonArray(textParts + images)))
                    }
                })
            }
            MessageListPageGuard.bound(
                MessageListIdentity.enrich(turnIdentity, convId, projected),
                newestLast = param(params, AdminParamKey("order"))?.lowercase() != "desc",
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
     * Walks newest-first pages up to [MESSAGE_GET_MAX_SEARCHABLE_MESSAGES].
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
        val message = try {
            kotlinx.coroutines.withTimeout(messageGetBudgetMs().milliseconds) {
                walkMessagePages(nativeClient, conversationId, messageId, op)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            adminError(
                "deadline_exceeded: message $messageId lookup exceeded searchable window budget",
            )
        }
        // message.get is a generic single-message lookup, not the "give me the
        // full tool output" pointer target that message.list's truncation
        // stamps point at — that's tool_return.get, which must keep returning
        // the untruncated body by design. Without this, a message.get on a row
        // with a large tool_return/tool_returns/stdout body can blow the 1MiB
        // admin_rpc frame the same way an unprojected message.list page used
        // to (letta-mobile-fe51r), so apply the same projection here.
        if (op != NativeAdminOp.MessageGet || message !is JsonObject) return message
        return MessageListWireProjection.projectMessage(message, conversationId) ?: message
    }

    private suspend fun walkMessagePages(
        nativeClient: AppServerClient?,
        conversationId: String,
        messageId: String,
        op: NativeAdminOp,
    ): JsonElement {
        var before: String? = null
        val pageLimit = messageGetPageLimit()
        val maxPages = (MESSAGE_GET_MAX_SEARCHABLE_MESSAGES + pageLimit - 1) / pageLimit
        repeat(maxPages) {
            val messages = NativeAdmin.require(nativeClient, op) { c ->
                val native = c.conversationMessagesList(
                    nativeMessageList(
                        ConversationId(conversationId),
                        "limit" to pageLimit.toString(),
                        "order" to "desc",
                        "before" to before,
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
        if (!context.canAccessConversation(conversationId) || !aliasOwnerInScope(context, conversationId)) {
            adminError("forbidden: conversation out of authorized scope")
        }
    }

    /** A default-conversation alias is readable only when its owning agent is in the caller's scope. */
    private fun aliasOwnerInScope(context: AdminRpcRequestContext, conversationId: String): Boolean {
        val owner = resolveConversationReadTarget(ConversationId(conversationId))?.second ?: return true
        val scope = context.authorizedAgentIds ?: return true
        return IrohPeerCapabilities.isAgentInScope(scope, owner.value)
    }

    /**
     * The App Server's message-list command for [conversation]: a default-conversation alias is
     * sent as the native default conversation with its owning agent, any other id as is.
     */
    private fun nativeMessageList(
        conversation: ConversationId,
        vararg query: Pair<String, String?>,
    ): AppServerCommand.ConversationMessagesList {
        val target = resolveConversationReadTarget(conversation)
        return AppServerCommand.ConversationMessagesList(
            requestId = NativeAdmin.requestId(),
            conversationId = (target?.first ?: conversation).value,
            query = NativeAdmin.queryOf("agent_id" to target?.second?.value, *query),
        )
    }

    /** Conversation lists past this are not held: about 1 KB per row, so roughly 20 MB at the cap. */
    private const val MAX_HELD_CONVERSATIONS = 20_000

    /** How many of the newest local messages `message.list` looks in for images to restore. */
    private const val ENRICHMENT_WINDOW = 64
}
