package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.chat.runtime.ApprovalSubmittingGateway
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.chat.runtime.ConnectionStatusGateway
import com.letta.mobile.data.chat.runtime.ConversationSummaryGateway
import com.letta.mobile.data.chat.send.OutboundMessageCreate
import com.letta.mobile.data.chat.send.lettaWireJson
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.ApprovalCreate
import com.letta.mobile.data.model.ApprovalSubmission
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.AppServerListModelsAdapter
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.runtime.terminalReasonKind
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Duration.Companion.milliseconds

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
    deviceLabel: String = "iroh-chat-gateway",
    private val heartbeatIntervalMs: Long = STREAM_HEARTBEAT_INTERVAL_MS,
) : ChatGateway, ChatGatewayExtras, ConversationSummaryGateway, ApprovalSubmittingGateway, ConnectionStatusGateway {

    private val deviceLabel: IrohDeviceLabel = IrohDeviceLabel(deviceLabel)

    override val connectionState: kotlinx.coroutines.flow.StateFlow<com.letta.mobile.data.transport.ChannelTransportState>
        get() = transport.state

    private val bridge = WsChatBridge(transport)
    private val json = lettaWireJson

    override suspend fun submitApproval(
        agentId: String,
        conversationId: String,
        approvalRequestId: String,
        toolCallId: String?,
        approve: Boolean,
        reason: String?,
    ) {
        val decoded = if (approve) AskUserQuestion.decodeAnswerReason(reason) else null
        val effectiveReason = if (decoded != null) null else reason?.takeIf { it.isNotBlank() }
        val approvalCreate = ApprovalCreate(
            approvals = toolCallId?.let {
                listOf(ApprovalSubmission(toolCallId = it, approve = approve, reason = effectiveReason))
            },
            approve = approve,
            approvalRequestId = approvalRequestId,
            reason = effectiveReason,
            updatedInput = decoded,
        )
        val payload = MessageCreateRequest(
            messages = listOf(json.encodeToJsonElement(ApprovalCreate.serializer(), approvalCreate)),
            streaming = false,
        )
        val body = buildJsonObject {
            put("agent_id", agentId)
            conversationId.takeIf { it.isNotBlank() }?.let { put("conversation_id", it) }
            put("payload", json.encodeToJsonElement(MessageCreateRequest.serializer(), payload))
        }
        val response = transport.adminRpc(
            method = "approval.submit",
            path = "/v1/agents/$agentId/messages",
            body = body.toString(),
        )
        if (!response.success) error(response.error ?: "Iroh admin_rpc approval.submit failed")
    }

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
        val result = rpc(
            AdminRpcCall.of(
                method = AdminRpcMethod("conversation.list"),
                path = AdminRpcPath("/v1/conversations"),
                body = AdminRpcBody(body),
            ),
        ) ?: return emptyList()

        if (result is JsonObject) {
            Telemetry.event(
                "IrohGate", "conversation_list.unexpected_object_shape",
                "keyCount" to result.size,
                "keys" to result.keys.sorted().take(12).joinToString(","),
                level = Telemetry.Level.WARN,
            )
        }
        return json.decodeFromJsonElement(ListSerializer(Conversation.serializer()), result)
            .also { conversations ->
                conversations.forEach { agentIdByConversation[it.id] = it.agentId }
            }
    }

    override suspend fun listConversationsForAgent(
        agentId: String,
        limit: Int,
    ): List<Conversation> {
        val body = buildJsonObject {
            put("agent_id", agentId)
            put("limit", limit.toString())
        }.toString()
        val result = runCatching {
            rpc(
                AdminRpcCall.of(
                    method = AdminRpcMethod("conversation.list_agent"),
                    path = AdminRpcPath("/v1/conversations/list_agent"),
                    body = AdminRpcBody(body),
                ),
            )
        }.getOrNull() ?: return emptyList()
        if (result !is JsonArray) {
            if (result is JsonObject) {
                Telemetry.event(
                    "IrohGate", "conversation_list_agent.unexpected_object_shape",
                    "keyCount" to result.size,
                    level = Telemetry.Level.WARN,
                )
            }
            return emptyList()
        }
        return runCatching {
            json.decodeFromJsonElement(ListSerializer(Conversation.serializer()), result)
                .also { conversations ->
                    conversations.forEach { agentIdByConversation[it.id] = it.agentId }
                }
        }.getOrElse { emptyList() }
    }

    override suspend fun getConversation(conversationId: String): Conversation {
        val result = rpc(
            AdminRpcCall.of(
                method = AdminRpcMethod("conversation.get"),
                path = AdminRpcPath("/v1/conversations/$conversationId"),
            ),
        ) ?: throw TimelineTransportHttpException(502, "conversation.get returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(Conversation.serializer(), result)
            .also { agentIdByConversation[it.id] = it.agentId }
    }

    override suspend fun deleteConversation(conversationId: String) {
        rpc(
            AdminRpcCall.of(
                method = AdminRpcMethod("conversation.archive"),
                path = AdminRpcPath("/v1/conversations/$conversationId"),
            ),
        )
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
        val result = rpc(
            AdminRpcCall.of(
                method = AdminRpcMethod("message.list"),
                path = AdminRpcPath(path),
            ),
        ) ?: return emptyList()

        val messagesElement = (result as? JsonObject)?.get("messages") ?: result
        if (messagesElement is JsonObject) {
            Telemetry.event(
                "IrohGate", "message_list.unexpected_object_shape",
                "conversationId" to conversationId,
                "keyCount" to messagesElement.size,
                "keys" to messagesElement.keys.sorted().take(12).joinToString(","),
                level = Telemetry.Level.WARN,
            )
        }
        return json.decodeFromJsonElement(ListSerializer(LettaMessage.serializer()), messagesElement)
    }

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> {
        Telemetry.event(
            "IrohChatGateway", "listAgentMessages.gated",
            "agentId" to agentId,
            "conversationId" to (conversationId ?: "<null>"),
        )
        return emptyList()
    }

    override suspend fun getToolReturn(conversationId: String, messageId: String): LettaMessage? {
        val result = rpc(
            AdminRpcCall.of(
                method = AdminRpcMethod("tool_return.get"),
                path = AdminRpcPath("/v1/conversations/$conversationId/messages/$messageId"),
            ),
        ) ?: return null
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
        val result = rpc(
            AdminRpcCall.of(
                method = AdminRpcMethod("conversation.create"),
                path = AdminRpcPath("/v1/conversations"),
                body = AdminRpcBody(body),
            ),
        ) ?: throw TimelineTransportHttpException(502, "conversation.create returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(Conversation.serializer(), result)
            .also { agentIdByConversation[it.id] = it.agentId }
    }

    override suspend fun createAgent(params: AgentCreateParams): Agent {
        val body = json.encodeToString(AgentCreateParams.serializer(), params)
        val result = rpc(
            AdminRpcCall.of(
                method = AdminRpcMethod("agent.create"),
                path = AdminRpcPath("/v1/agents"),
                body = AdminRpcBody(body),
            ),
        ) ?: throw TimelineTransportHttpException(502, "agent.create returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(Agent.serializer(), result)
    }

    override suspend fun listLlmModels(): List<LlmModel> {
        val result = rpc(
            AdminRpcCall.of(
                method = AdminRpcMethod("model.list"),
                path = AdminRpcPath("/v1/models"),
                body = AdminRpcBody("{}"),
            ),
        ) ?: return emptyList()
        val array = result as? JsonArray ?: return emptyList()
        return AppServerListModelsAdapter.toLlmModels(array)
    }

    override suspend fun setConversationSummary(update: ConversationSummaryUpdate): Conversation {
        val body = buildJsonObject { put("summary", update.summary.value) }.toString()
        val result = rpc(
            AdminRpcCall.of(
                method = AdminRpcMethod("conversation.update"),
                path = AdminRpcPath("/v1/conversations/${update.conversationId.value}"),
                body = AdminRpcBody(body),
            ),
        ) ?: throw TimelineTransportHttpException(502, "conversation.update returned no result over iroh admin_rpc")
        return json.decodeFromJsonElement(Conversation.serializer(), result)
    }

    override suspend fun setConversationModel(conversationId: String, model: String): Conversation {
        throw UnsupportedOperationException("Per-conversation model override is not available over iroh:// yet")
    }

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation {
        val method = if (archived) AdminRpcMethod("conversation.archive") else AdminRpcMethod("conversation.restore")
        val result = rpc(
            AdminRpcCall.of(
                method = method,
                path = AdminRpcPath("/v1/conversations/$conversationId"),
            ),
        ) ?: throw TimelineTransportHttpException(502, "$method returned no result over iroh admin_rpc")
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

    private fun dispatchSend(conversationId: ConversationId, agentId: AgentId, outbound: OutboundMessageCreate) {
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
            "device" to deviceLabel.value,
        )
    }

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> {
        val frames = flow {
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
        return merge(frames, connectedHeartbeats())
    }

    private fun connectedHeartbeats(): Flow<TimelineStreamFrame> = flow {
        while (isTransportConnected()) {
            delay(heartbeatIntervalMs.milliseconds)
            if (!isTransportConnected()) break
            emit(TimelineStreamFrame.Heartbeat)
        }
    }

    private fun isTransportConnected(): Boolean =
        transport.state.value is com.letta.mobile.data.transport.ChannelTransportState.Connected

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

    private class SendTurn(private val conversationId: ConversationId) {
        private var turnId: IrohTurnId? = null
        private val terminal = CompletableDeferred<Unit>()
        private var deliveredAssistantContent: Boolean = false
        private var mainReplyCompleted: Boolean = false
        private var failureReason: IrohFailureReason? = null
        private var failureKind: IrohFailureKind? = null

        suspend fun route(event: WsTimelineEvent, emit: suspend (LettaMessage) -> Unit) {
            when (event) {
                is WsTimelineEvent.TurnStarted -> onTurnStarted(event)
                is WsTimelineEvent.MessageDelta -> onMessageDelta(event, emit)
                is WsTimelineEvent.StopReason -> onStopReason(event)
                is WsTimelineEvent.TurnDone -> onTurnDone(event, emit)
                is WsTimelineEvent.Error -> onError(event, emit)
                is WsTimelineEvent.Disconnected -> onDisconnected(event)
                else -> Unit
            }
        }

        suspend fun awaitTerminal() = terminal.await()

        private fun onTurnStarted(event: WsTimelineEvent.TurnStarted) {
            if (event.conversationId == conversationId.value && turnId == null) {
                turnId = event.turnId?.let(::IrohTurnId)
            }
        }

        private fun onStopReason(event: WsTimelineEvent.StopReason) {
            val ownedTurnId = turnId ?: return
            if (event.turnId != ownedTurnId.value) return
            if (TurnFailureNotices.isCompletedMainReplyStopReason(event.stopReason)) {
                mainReplyCompleted = true
            }
        }

        private suspend fun onMessageDelta(event: WsTimelineEvent.MessageDelta, emit: suspend (LettaMessage) -> Unit) {
            val belongsToTurn = turnId != null &&
                (event.conversationId == null || event.conversationId == conversationId.value)
            if (!belongsToTurn) return
            val message = event.message
            if (message is AssistantMessage && message.content.isNotBlank()) {
                deliveredAssistantContent = true
            }
            emit(message)
        }

        private suspend fun onTurnDone(event: WsTimelineEvent.TurnDone, emit: suspend (LettaMessage) -> Unit) {
            val ownedTurnId = turnId ?: return
            if (event.turnId != ownedTurnId.value) return
            if (event.status == "failed") {
                failTurn(emit, IrohFailureDetail("Iroh turn failed (turnId=${ownedTurnId.value})"))
            } else {
                terminal.complete(Unit)
            }
        }

        private suspend fun onError(event: WsTimelineEvent.Error, emit: suspend (LettaMessage) -> Unit) {
            if (event.conversationId != null && event.conversationId != conversationId.value) return
            if (event.turnId != null && turnId != null && event.turnId != turnId?.value) return
            if (event.conversationId == null && event.turnId == null && turnId != null) return
            val rawReason = event.message.ifBlank { event.code }
            failureReason = IrohFailureReason(rawReason)
            failureKind = event.code.takeIf { TurnFailureNotices.isKnownKind(it) }?.let(::IrohFailureKind)
            failTurn(emit, IrohFailureDetail("Iroh turn error ${event.code}: ${event.message}"))
        }

        private suspend fun failTurn(emit: suspend (LettaMessage) -> Unit, detail: IrohFailureDetail) {
            if (terminal.isCompleted) return
            val notice = TurnFailureNotices.forFailedTerminal(
                reason = failureReason?.value,
                deliveredAssistantContent = deliveredAssistantContent,
                mainReplyCompleted = mainReplyCompleted,
                kindHint = failureKind?.value,
            )
            if (notice == null) {
                Telemetry.event(
                    "IrohChatGateway", "turn.failedAfterDelivery",
                    "conversationId" to conversationId.value,
                    "turnId" to (turnId?.value ?: ""),
                    "reasonKind" to (failureKind?.value ?: terminalReasonKind(failureReason?.value) ?: "<none>"),
                )
                terminal.complete(Unit)
                return
            }
            emit(
                ErrorMessage(
                    id = "turn-failed-${turnId?.value ?: conversationId.value}",
                    contentRaw = JsonPrimitive(notice.message),
                    code = notice.kind,
                ),
            )
            terminal.completeExceptionally(TimelineTransportHttpException(502, detail.value))
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
        const val STREAM_HEARTBEAT_INTERVAL_MS = 15_000L
    }
}
