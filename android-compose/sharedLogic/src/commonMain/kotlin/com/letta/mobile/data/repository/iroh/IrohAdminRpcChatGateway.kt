package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.MessageCreate
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Server sends explicit nulls for optional fields (metadata: null etc.) —
// explicitNulls=false + coerceInputValues=true coerce those to property
// defaults instead of failing decode (same config as the Android sources).
private val irohAdminJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
}

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
    private val json = irohAdminJson

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
        return json.decodeFromJsonElement(ListSerializer(LettaMessage.serializer()), result)
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
        val result = rpc(AdminRpcCall("conversation.create", "/v1/agents/$agentId/conversations", body))
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
        val outbound = decodeOutbound(request)
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

    private suspend fun dispatchSend(conversationId: ConversationId, agentId: AgentId, outbound: OutboundMessage) {
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

    private data class OutboundMessage(
        val text: String,
        val otid: String?,
        val contentParts: JsonArray?,
    )

    private fun decodeOutbound(request: MessageCreateRequest): OutboundMessage {
        val element = request.messages?.firstOrNull()
            ?: return OutboundMessage(text = request.input.orEmpty(), otid = null, contentParts = null)
        val create = json.decodeFromJsonElement(MessageCreate.serializer(), element)
        return when (val content = create.content) {
            is JsonPrimitive -> OutboundMessage(
                text = content.contentOrNull.orEmpty(),
                otid = create.otid,
                contentParts = null,
            )
            is JsonArray -> OutboundMessage(
                text = content.firstTextPart().orEmpty(),
                otid = create.otid,
                contentParts = content,
            )
            else -> OutboundMessage(text = "", otid = create.otid, contentParts = null)
        }
    }

    private fun JsonArray.firstTextPart(): String? = asSequence()
        .filterIsInstance<JsonObject>()
        .firstOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull == "text" }
        ?.let { (it["text"] as? JsonPrimitive)?.contentOrNull }

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
    private val json = irohAdminJson

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

    companion object {
        // Single page sized for the desktop roster; Android's source paginates
        // 50-at-a-time up to 2500 — revisit if a deployment outgrows this.
        const val AGENT_LIST_LIMIT = 200
    }
}
