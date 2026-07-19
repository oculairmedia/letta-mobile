package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.send.OutboundMessageCreate
import com.letta.mobile.data.chat.send.lettaWireJson
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.model.Block
import com.letta.mobile.data.model.BlockCreateParams
import com.letta.mobile.data.model.BlockUpdateParams
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ScheduleCreateParams
import com.letta.mobile.data.model.ScheduleListResponse
import com.letta.mobile.data.model.ScheduledMessage
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolCreateParams
import com.letta.mobile.data.model.ToolUpdateParams
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import com.letta.mobile.data.transport.api.IChannelTransport
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One `admin_rpc` invocation: the registry method plus the HTTP-equivalent
 * path (and optional JSON body) the server-side handler expects — the same
 * conventions the Android IrohAdminRpc*Source classes use.
 */
private data class AdminRpcCall(
    val method: String,
    val path: String,
    val body: String? = null,
)

/**
 * [ChatGateway] served entirely over an Iroh [IChannelTransport] — no HTTP.
 *
 * Reads (conversation/message/model listing) go over `admin_rpc`, mirroring
 * the method/path/body conventions of the Android IrohAdminRpc*Source
 * classes so both clients hit the same server handlers
 * (ConversationAdminHandlers / ModelAdminHandlers / AgentAdminHandlers).
 *
 * Live chat rides the transport's frame stream: [sendConversationMessage]
 * dispatches through [IChannelTransport.send] and returns a flow of the
 * turn's message deltas that completes on `turn_done` — the same contract
 * the SSE gateway satisfies, so TimelineSyncLoop's send/mark-sent/reconcile
 * pipeline works unchanged. [streamConversation] is the persistent
 * subscriber view of the same frames, with synthesized heartbeats standing
 * in for SSE pings so the loop's silence timeout doesn't cycle the
 * subscription while the agent is idle (letta-mobile-yh92w).
 */
class IrohAdminRpcChatGateway(
    private val transport: IChannelTransport,
    private val deviceLabel: String = "iroh-chat-gateway",
    private val heartbeatIntervalMs: Long = STREAM_HEARTBEAT_INTERVAL_MS,
) : ChatGateway, ChatGatewayExtras {

    private val bridge = WsChatBridge(transport)
    private val json = lettaWireJson

    /** conversationId -> agentId, learned from conversation.get/list. */
    private val agentIdByConversation = mutableMapOf<ConversationId, AgentId>()

    // ------------------------------------------------------------------
    // ChatGateway — admin_rpc reads
    // ------------------------------------------------------------------

    override suspend fun listConversations(limit: Int, archiveStatus: String?): List<Conversation> {
        val body = buildJsonObject {
            put("limit", limit.toString())
            archiveStatus?.let { put("archive_status", it) }
            put("order", "desc")
            put("order_by", "last_message_at")
        }.toString()
        val result = rpc(AdminRpcCall("conversation.list", "/v1/conversations", body)) ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Conversation.serializer()), result)
            .also { conversations ->
                conversations.forEach { agentIdByConversation[it.id] = it.agentId }
            }
    }

    override suspend fun getConversation(conversationId: String): Conversation {
        val result = rpc(AdminRpcCall("conversation.get", "/v1/conversations/$conversationId"))
            ?: throw TimelineTransportHttpException(502, "conversation.get returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(Conversation.serializer(), result)
            .also { agentIdByConversation[it.id] = it.agentId }
    }

    override suspend fun deleteConversation(conversationId: String) {
        rpc(AdminRpcCall("conversation.delete", "/v1/conversations/$conversationId"))
        agentIdByConversation.remove(ConversationId(conversationId))
    }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        val query = listOfNotNull(
            limit?.let { "limit=$it" },
            after?.let { "after=$it" },
            order?.let { "order=$it" },
        ).joinToString("&")
        val path = "/v1/conversations/$conversationId/messages" +
            (if (query.isEmpty()) "" else "?$query")
        val result = rpc(AdminRpcCall("message.list", path)) ?: return emptyList()
        // letta-mobile-c4igq.9: the serve-side page guard returns a bare array when
        // the page fits, or { messages: [...], has_more, next_before } when it had to
        // trim an oversized window. Accept both so a bounded page still decodes and
        // renders (older windows load via the existing before-cursor pager).
        val messagesElement = (result as? kotlinx.serialization.json.JsonObject)
            ?.get("messages") ?: result
        return json.decodeFromJsonElement(ListSerializer(LettaMessage.serializer()), messagesElement)
    }

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> {
        // No agent-scoped message.list handler exists server-side over Iroh.
        // Degrade to empty (no reachable history) rather than throwing — a throw
        // surfaces a "Message load failed" banner when a default-shim agent
        // conversation is opened, whereas empty leaves it functional for live
        // send/stream. Live turns still flow through the frame path.
        Telemetry.event(
            "IrohChatGateway", "listAgentMessages.gated",
            "agentId" to agentId,
            "conversationId" to (conversationId ?: "<null>"),
        )
        return emptyList()
    }

    override suspend fun getToolReturn(conversationId: String, messageId: String): LettaMessage? {
        val result = rpc(AdminRpcCall("tool_return.get", "/v1/conversations/$conversationId/messages/$messageId"))
            ?: return null
        return json.decodeFromJsonElement(LettaMessage.serializer(), result)
    }

    // ------------------------------------------------------------------
    // ChatGatewayExtras — admin_rpc management
    // ------------------------------------------------------------------

    override suspend fun createConversation(agentId: String, summary: String?): Conversation {
        val body = buildJsonObject {
            put("agent_id", agentId)
            summary?.let { put("summary", it) }
        }.toString()
        val result = rpc(AdminRpcCall("conversation.create", "/v1/conversations", body))
            ?: throw TimelineTransportHttpException(502, "conversation.create returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(Conversation.serializer(), result)
            .also { agentIdByConversation[it.id] = it.agentId }
    }

    override suspend fun createAgent(params: AgentCreateParams): Agent {
        val body = json.encodeToString(AgentCreateParams.serializer(), params)
        val result = rpc(AdminRpcCall("agent.create", "/v1/agents", body))
            ?: throw TimelineTransportHttpException(502, "agent.create returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(Agent.serializer(), result)
    }

    override suspend fun listLlmModels(): List<LlmModel> {
        val result = rpc(AdminRpcCall("model.list", "/v1/models", "{}")) ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(LlmModel.serializer()), result)
    }

    override suspend fun setConversationModel(conversationId: String, model: String): Conversation {
        // No conversation-update admin_rpc handler is registered yet.
        throw UnsupportedOperationException("Per-conversation model override is not available over iroh:// yet")
    }

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation {
        val method = if (archived) "conversation.archive" else "conversation.restore"
        val result = rpc(AdminRpcCall(method, "/v1/conversations/$conversationId"))
            ?: throw TimelineTransportHttpException(502, "$method returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(Conversation.serializer(), result)
    }

    // ------------------------------------------------------------------
    // Send + live stream — transport frames
    // ------------------------------------------------------------------

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> {
        val conversation = ConversationId(conversationId)
        val agentId = agentIdFor(conversation)
        val outbound = OutboundMessageCreate.decode(request)
        return channelFlow {
            val turn = SendTurn(conversation)
            // UNDISPATCHED so the frame subscription is live before send()
            // dispatches the turn — otherwise a fast turn_started could race past us.
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                bridge.events.collect { event -> turn.route(event) { message -> send(message) } }
            }
            try {
                dispatchSend(conversation, agentId, outbound)
                turn.awaitTerminal()
            } finally {
                collector.cancel()
            }
        }
    }

    private suspend fun dispatchSend(conversationId: ConversationId, agentId: AgentId, outbound: OutboundMessageCreate) {
        val accepted = transport.send(
            agentId = agentId.value,
            conversationId = conversationId.value,
            text = outbound.text,
            otid = outbound.otid,
            contentParts = outbound.contentParts,
        )
        if (!accepted) {
            throw TimelineTransportHttpException(409, "Iroh transport rejected send (turn already in flight?)")
        }
        Telemetry.event(
            "IrohChatGateway", "send.dispatched",
            "conversationId" to conversationId.value,
            "agentId" to agentId.value,
            "otid" to (outbound.otid ?: "<null>"),
            "device" to deviceLabel,
        )
    }

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> {
        val frames = flow {
            // letta-mobile-r3i1z: route each delta by the frame's OWN
            // conversation_id when it carries one. Server-side fanout writes
            // every turn frame to every registered viewer, and for a passive
            // observer the user_message echo lands BEFORE turn_started — an
            // active-turn gate alone would drop it (and, more generally, made
            // ingestion depend on this client having initiated the turn).
            // Frames that genuinely lack a conversation id fall back to the
            // turn that most recently started (the same turn-scoped
            // resolution ChatSendCoordinator uses on mobile); when the frame
            // IS tagged, its tag is authoritative, so another conversation's
            // fanned-out frames can never leak into this stream.
            var activeTurnConversationId: String? = null
            bridge.events.collect { event ->
                when (event) {
                    is WsTimelineEvent.TurnStarted -> activeTurnConversationId = event.conversationId
                    is WsTimelineEvent.MessageDelta -> {
                        val belongs = event.conversationId?.let { it == conversationId }
                            ?: (activeTurnConversationId == conversationId)
                        if (belongs) {
                            emit(TimelineStreamFrame.Message(event.message))
                        }
                    }
                    else -> Unit
                }
            }
        }
        val heartbeats = flow<TimelineStreamFrame> {
            while (true) {
                delay(heartbeatIntervalMs)
                emit(TimelineStreamFrame.Heartbeat)
            }
        }
        return merge(frames, heartbeats)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private suspend fun agentIdFor(conversationId: ConversationId): AgentId =
        agentIdByConversation[conversationId] ?: getConversation(conversationId.value).agentId

    private suspend fun rpc(call: AdminRpcCall): JsonElement? {
        val response = transport.adminRpc(method = call.method, path = call.path, body = call.body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "${call.method} failed over iroh admin_rpc")
        }
        return response.result
    }

    /**
     * Tracks a single outbound turn over the shared frame stream: captures the
     * turn id from the matching `turn_started`, forwards this conversation's
     * message deltas to [emit], and completes [terminal] on turn_done / error /
     * terminal disconnect. Extracted from [sendConversationMessage] so each
     * frame case stays flat (one guarded statement per event).
     */
    private class SendTurn(private val conversationId: ConversationId) {
        private var turnId: String? = null
        private val terminal = CompletableDeferred<Unit>()

        suspend fun route(event: WsTimelineEvent, emit: suspend (LettaMessage) -> Unit) {
            when (event) {
                is WsTimelineEvent.TurnStarted -> onTurnStarted(event)
                is WsTimelineEvent.MessageDelta -> onMessageDelta(event, emit)
                is WsTimelineEvent.TurnDone -> onTurnDone(event)
                is WsTimelineEvent.Error -> onError(event)
                is WsTimelineEvent.Disconnected -> onDisconnected(event)
                else -> Unit
            }
        }

        suspend fun awaitTerminal() = terminal.await()

        private fun onTurnStarted(event: WsTimelineEvent.TurnStarted) {
            if (event.conversationId == conversationId.value && turnId == null) {
                turnId = event.turnId
            }
        }

        // r3i1z: with server-side fanout, frames for OTHER conversations this
        // client observes can arrive mid-turn. A conversation-tagged delta must
        // match ours; untagged deltas keep the legacy own-turn scoping.
        private suspend fun onMessageDelta(event: WsTimelineEvent.MessageDelta, emit: suspend (LettaMessage) -> Unit) {
            val belongsToTurn = turnId != null &&
                (event.conversationId == null || event.conversationId == conversationId.value)
            if (belongsToTurn) emit(event.message)
        }

        private fun onTurnDone(event: WsTimelineEvent.TurnDone) {
            if (turnId == null || event.turnId != turnId) return
            if (event.status == "failed") {
                terminal.completeExceptionally(
                    TimelineTransportHttpException(502, "Iroh turn failed (turnId=$turnId)"),
                )
            } else {
                terminal.complete(Unit)
            }
        }

        private fun onError(event: WsTimelineEvent.Error) {
            val forThisTurn = event.conversationId == conversationId.value ||
                (turnId != null && event.turnId == turnId)
            if (forThisTurn) {
                terminal.completeExceptionally(
                    TimelineTransportHttpException(502, "Iroh turn error ${event.code}: ${event.message}"),
                )
            }
        }

        private fun onDisconnected(event: WsTimelineEvent.Disconnected) {
            if (!event.willReconnect) {
                terminal.completeExceptionally(
                    TimelineTransportHttpException(0, "Iroh transport disconnected: ${event.reason}"),
                )
            }
        }
    }

    companion object {
        /**
         * Iroh emits no idle pings; synthesize heartbeats under the timeline
         * loop's stream-silence timeout so idle conversations don't cycle
         * the subscriber.
         */
        const val STREAM_HEARTBEAT_INTERVAL_MS = 15_000L
    }
}

/**
 * Minimal agent directory over iroh admin_rpc — enough for the desktop
 * shell's agent-name/model lookups without the HTTP admin repositories.
 */
class IrohAdminRpcAgentDirectory(
    private val transport: IChannelTransport,
) {
    private val json = lettaWireJson

    suspend fun listAgents(limit: Int = AGENT_LIST_LIMIT): List<Agent> {
        val body = buildJsonObject {
            put("limit", limit.toString())
            put("offset", "0")
        }.toString()
        val call = AdminRpcCall("agent.list", "/v1/agents?limit=$limit&offset=0", body)
        val response = transport.adminRpc(call.method, call.path, call.body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "${call.method} failed over iroh admin_rpc")
        }
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Agent.serializer()), result)
    }

    suspend fun getAgent(agentId: String): Agent? {
        val body = buildJsonObject { put("agent_id", agentId) }.toString()
        val call = AdminRpcCall("agent.get", "/v1/agents/$agentId", body)
        val response = transport.adminRpc(call.method, call.path, call.body)
        if (!response.success) return null
        val result = response.result ?: return null
        return json.decodeFromJsonElement(Agent.serializer(), result)
    }

    suspend fun updateAgent(agentId: String, params: AgentUpdateParams): Agent {
        val paramsJson = json.encodeToJsonElement(AgentUpdateParams.serializer(), params).jsonObject
        val body = buildJsonObject {
            put("agent_id", agentId)
            paramsJson.forEach { (key, value) -> put(key, value) }
        }.toString()
        val response = transport.adminRpc("agent.update", "/v1/agents/$agentId", body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "agent.update failed over iroh admin_rpc")
        }
        val result = response.result ?: throw TimelineTransportHttpException(502, "agent.update returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(Agent.serializer(), result)
    }

    suspend fun getContextWindow(agentId: String, conversationId: String? = null): ContextWindowOverview {
        val body = buildJsonObject {
            put("agent_id", agentId)
            conversationId?.let { put("conversation_id", it) }
        }.toString()
        val path = buildString {
            append("/v1/agents/")
            append(agentId)
            append("/context")
            if (conversationId != null) append("?conversation_id=").append(conversationId)
        }
        val response = transport.adminRpc("agent.context", path, body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "agent.context failed over iroh admin_rpc")
        }
        val result = response.result ?: throw TimelineTransportHttpException(502, "agent.context returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(ContextWindowOverview.serializer(), result)
    }

    suspend fun listTools(limit: Int, offset: Int): List<Tool> {
        val body = buildJsonObject {
            put("limit", limit)
            put("offset", offset)
        }.toString()
        val response = transport.adminRpc("tool.list", "/v1/tools?limit=$limit&offset=$offset", body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "tool.list failed over iroh admin_rpc")
        }
        val result = response.result ?: return emptyList()
        return json.decodeFromJsonElement(ListSerializer(Tool.serializer()), result)
    }

    suspend fun createTool(params: ToolCreateParams): Tool {
        val body = json.encodeToString(ToolCreateParams.serializer(), params)
        val response = transport.adminRpc("tool.create", "/v1/tools", body)
        if (!response.success) throw TimelineTransportHttpException(502, response.error ?: "tool.create failed over iroh admin_rpc")
        val result = response.result ?: throw TimelineTransportHttpException(502, "tool.create returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(Tool.serializer(), result)
    }

    suspend fun updateTool(toolId: String, params: ToolUpdateParams): Tool {
        val paramsJson = json.encodeToJsonElement(ToolUpdateParams.serializer(), params).jsonObject
        val body = buildJsonObject {
            put("tool_id", toolId)
            paramsJson.forEach { (key, value) -> put(key, value) }
        }.toString()
        val response = transport.adminRpc("tool.update", "/v1/tools/$toolId", body)
        if (!response.success) throw TimelineTransportHttpException(502, response.error ?: "tool.update failed over iroh admin_rpc")
        val result = response.result ?: throw TimelineTransportHttpException(502, "tool.update returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(Tool.serializer(), result)
    }

    suspend fun deleteTool(toolId: String) {
        val body = buildJsonObject { put("tool_id", toolId) }.toString()
        val response = transport.adminRpc("tool.delete", "/v1/tools/$toolId", body)
        if (!response.success) throw TimelineTransportHttpException(502, response.error ?: "tool.delete failed over iroh admin_rpc")
    }

    suspend fun setToolAttached(agentId: String, toolId: String, attached: Boolean) {
        val body = buildJsonObject {
            put("agent_id", agentId)
            put("tool_id", toolId)
        }.toString()
        val method = if (attached) "tool.attach" else "tool.detach"
        val action = if (attached) "attach" else "detach"
        val response = transport.adminRpc(method, "/v1/agents/$agentId/tools/$action/$toolId", body)
        if (!response.success) throw TimelineTransportHttpException(502, response.error ?: "$method failed over iroh admin_rpc")
    }

    suspend fun listSkills(agentId: String? = null): List<Skill> {
        val body = buildJsonObject {
            agentId?.let { put("agent_id", it) }
        }.toString()
        val method = if (agentId == null) "skill.list" else "skill.list_agent"
        val path = agentId?.let { "/v1/agents/$it/skills" } ?: "/v1/skills"
        val response = transport.adminRpc(method, path, body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "$method failed over iroh admin_rpc")
        }
        val result = response.result ?: return emptyList()
        val skillsElement = (result as? kotlinx.serialization.json.JsonObject)?.get("skills") ?: result
        return json.decodeFromJsonElement(ListSerializer(Skill.serializer()), skillsElement)
    }

    suspend fun installSkill(agentId: String, skillName: String) {
        val body = buildJsonObject {
            put("agent_id", agentId)
            put("name", skillName)
        }.toString()
        val response = transport.adminRpc("skill.install", "/v1/agents/$agentId/skills", body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "skill.install failed over iroh admin_rpc")
        }
    }

    suspend fun uninstallSkill(agentId: String, skillName: String) {
        val body = buildJsonObject {
            put("agent_id", agentId)
            put("name", skillName)
        }.toString()
        val response = transport.adminRpc("skill.uninstall", "/v1/agents/$agentId/skills/$skillName", body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "skill.uninstall failed over iroh admin_rpc")
        }
    }

    suspend fun getBlock(blockId: String): Block? {
        val body = buildJsonObject { put("block_id", blockId) }.toString()
        val response = transport.adminRpc("block.get", "/v1/blocks/$blockId", body)
        if (!response.success) return null
        val result = response.result ?: return null
        return json.decodeFromJsonElement(Block.serializer(), result)
    }

    suspend fun createBlock(params: BlockCreateParams): Block {
        val body = json.encodeToString(BlockCreateParams.serializer(), params)
        val response = transport.adminRpc("block.create", "/v1/blocks", body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "block.create failed over iroh admin_rpc")
        }
        val result = response.result ?: throw TimelineTransportHttpException(502, "block.create returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(Block.serializer(), result)
    }

    suspend fun updateBlock(blockId: String, params: BlockUpdateParams): Block {
        val body = buildJsonObject {
            put("block_id", blockId)
            params.value?.let { put("value", it) }
            params.limit?.let { put("limit", it) }
            params.description?.let { put("description", it) }
        }.toString()
        val response = transport.adminRpc("block.update", "/v1/blocks/$blockId", body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "block.update failed over iroh admin_rpc")
        }
        val result = response.result ?: throw TimelineTransportHttpException(502, "block.update returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(Block.serializer(), result)
    }

    suspend fun deleteBlock(blockId: String) {
        val body = buildJsonObject { put("block_id", blockId) }.toString()
        val response = transport.adminRpc("block.delete", "/v1/blocks/$blockId", body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "block.delete failed over iroh admin_rpc")
        }
    }

    suspend fun attachBlock(agentId: String, blockId: String) {
        val body = buildJsonObject {
            put("agent_id", agentId)
            put("block_id", blockId)
        }.toString()
        val response = transport.adminRpc("block.attach", "/v1/agents/$agentId/core-memory/blocks/attach/$blockId", body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "block.attach failed over iroh admin_rpc")
        }
    }

    suspend fun listSchedules(agentId: String? = null): List<ScheduledMessage> {
        val body = buildJsonObject {
            agentId?.let { put("agent_id", it) }
        }.toString()
        val path = agentId?.let { "/v1/agents/$it/schedule" } ?: "/v1/schedules"
        val response = transport.adminRpc("schedule.list", path, body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "schedule.list failed over iroh admin_rpc")
        }
        val result = response.result ?: return emptyList()
        val schedules = json.decodeFromJsonElement(ScheduleListResponse.serializer(), result).scheduledMessages
        return agentId?.let { id -> schedules.filter { it.agentId == id } } ?: schedules
    }

    suspend fun getSchedule(scheduleId: String, agentId: String? = null): ScheduledMessage? {
        val body = buildJsonObject {
            put("schedule_id", scheduleId)
            agentId?.let { put("agent_id", it) }
        }.toString()
        val path = agentId?.let { "/v1/agents/$it/schedule/$scheduleId" } ?: "/v1/schedules/$scheduleId"
        val response = transport.adminRpc("schedule.get", path, body)
        if (!response.success) return null
        val result = response.result ?: return null
        return json.decodeFromJsonElement(ScheduledMessage.serializer(), result)
    }

    suspend fun createSchedule(agentId: String, params: ScheduleCreateParams): ScheduledMessage {
        val paramsJson = json.encodeToJsonElement(ScheduleCreateParams.serializer(), params).jsonObject
        val body = buildJsonObject {
            put("agent_id", agentId)
            paramsJson.forEach { (key, value) -> put(key, value) }
        }.toString()
        val response = transport.adminRpc("schedule.create", "/v1/agents/$agentId/schedule", body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "schedule.create failed over iroh admin_rpc")
        }
        val result = response.result ?: throw TimelineTransportHttpException(502, "schedule.create returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(ScheduledMessage.serializer(), result)
    }

    suspend fun deleteSchedule(scheduleId: String, agentId: String? = null) {
        val body = buildJsonObject {
            put("schedule_id", scheduleId)
            agentId?.let { put("agent_id", it) }
        }.toString()
        val path = agentId?.let { "/v1/agents/$it/schedule/$scheduleId" } ?: "/v1/schedules/$scheduleId"
        val response = transport.adminRpc("schedule.delete", path, body)
        if (!response.success) {
            throw TimelineTransportHttpException(502, response.error ?: "schedule.delete failed over iroh admin_rpc")
        }
    }

    companion object {
        // Single page sized for the desktop roster; Android's source paginates
        // 50-at-a-time up to 2500 — revisit if a deployment outgrows this.
        const val AGENT_LIST_LIMIT = 200
    }
}
