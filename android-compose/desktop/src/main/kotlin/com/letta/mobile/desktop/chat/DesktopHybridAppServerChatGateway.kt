package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.send.OutboundMessageCreate
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.runtime.AppServerRuntimeEventMapper
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.iroh.RuntimeEventServerFrameMapper
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnEngine
import com.letta.mobile.runtime.TurnInput
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge

import kotlin.time.Duration.Companion.milliseconds
/**
 * Hybrid desktop chat gateway: live chat through the App Server TurnEngine,
 * listing/CRUD through HTTP.
 *
 * ROUTING:
 * - [sendConversationMessage]: resolves the conversation's agent, then drives
 *   [TurnEngine.runTurn] on an engine built Unrestricted (desktop has no
 *   approval UI; approvals auto-allow, matching the Android iroh engine —
 *   the mode is baked into the engine so its single runtime_start carries
 *   it, with no eager seed call) and projects the RuntimeEventDraft stream
 *   into LettaMessages via the SAME mappers the Android iroh path uses
 *   (RuntimeEventServerFrameMapper + WsFrameMapper), so ids/otids/prefixes are
 *   byte-identical and the shared timeline reducer dedups correctly.
 * - [streamConversation]: passive view of the App Server stream channel
 *   (stream_delta frames routed by their own runtime.conversation_id), with
 *   synthesized heartbeats so the sync loop's silence timeout doesn't cycle
 *   the subscription while the agent idles (same contract as
 *   IrohAdminRpcChatGateway.streamConversation). Heartbeats stop once
 *   [AppServerClient.isConnected] reports the client dropped.
 * - setConversationModel/setConversationArchived and the rest of
 *   [ChatGatewayExtras] delegate to the HTTP gateway (chat rides the App
 *   Server; management operations stay HTTP — same hybrid split as listing).
 * - conversation/message listing, agent CRUD, model catalog: HTTP gateway —
 *   the App Server exposes no listing APIs yet.
 *
 * LIFECYCLE: [close] tears down the HTTP gateway and, when this gateway rode
 * an iroh or WebSocket dial, the transport-level resources via
 * [DesktopTransportResources]. The engine/transport scope is owned by the
 * factory.
 */
class DesktopHybridAppServerChatGateway internal constructor(
    private val turnEngine: TurnEngine,
    private val client: AppServerClient,
    private val httpGateway: DesktopLettaHttpChatGateway,
    private val transportResources: DesktopTransportResources? = null,
    private val heartbeatIntervalMs: Long = IrohAdminRpcChatGateway.STREAM_HEARTBEAT_INTERVAL_MS,
    private val agentIdResolver: suspend (conversationId: String) -> String = { conversationId ->
        httpGateway.getConversation(conversationId).agentId.value
    },
) : DesktopChatGateway, ChatGatewayExtras by httpGateway, AutoCloseable {

    /** conversationId -> agentId, learned on first send/stream per conversation. */
    private val agentIdByConversation = ConcurrentHashMap<String, String>()

    /**
     * Conversations with a [sendConversationMessage] flow currently collecting.
     * The passive [observedStreamMessages] projection and the send flow both
     * project the same underlying frames but under DIFFERENT synthetic turnIds
     * (send: `desktop-turn-<uuid>`, stream: `desktop-stream-turn-<conv>`), so
     * their derived reasoning-message ids diverge and seq-based dedup misses
     * the duplicate. Mirrors Android's ingestObserverFrame active-turn guard:
     * while a conversation has a send in flight, the send flow is already
     * emitting its frames, so the passive observer drops frames for that
     * conversation at the source instead of re-emitting a duplicate bubble.
     */
    private val activeSendConversations = ConcurrentHashMap.newKeySet<String>()

    /**
     * Projects passively-observed [AppServerInboundFrame.StreamDelta] frames the
     * SAME way [IrohChannelTransport.ingestObserverFrame] does: raw StreamDelta ->
     * RuntimeEventDraft (this mapper turns client_tool_start/client_tool_end into
     * ToolCallObserved/ToolReturnObserved, not just RemoteStreamFrame) -> ServerFrame
     * (RuntimeEventServerFrameMapper) -> LettaMessage (WsFrameMapper). Without this
     * step, other-client-initiated tool calls/returns never produce timeline cards
     * because IrohStreamDeltaServerFrameMapper only understands
     * assistant_message/reasoning_message/tool_call_message/tool_return_message.
     */
    private val runtimeEventMapper = AppServerRuntimeEventMapper()

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> {
        val agentId = agentIdFor(conversationId)
        val outbound = OutboundMessageCreate.decode(request)
        val turnId = "desktop-turn-${UUID.randomUUID()}"
        val syntheticRunId = "desktop-run-${UUID.randomUUID()}"
        val command = TurnCommand(
            backendId = BackendId(APP_SERVER_BACKEND_ID),
            runtimeId = RuntimeId("$APP_SERVER_BACKEND_ID:$conversationId"),
            agentId = com.letta.mobile.data.model.AgentId(agentId),
            conversationId = ConversationId(conversationId),
            input = TurnInput.UserMessage(
                localMessageId = outbound.otid ?: "desktop-local-${UUID.randomUUID()}",
                text = outbound.text,
                contentPartsJson = outbound.contentParts?.toString(),
            ),
        )
        return flow {
            activeSendConversations.add(conversationId)
            try {
                turnEngine.runTurn(command).collect { draft ->
                    val lifecycle = draft.payload as? RuntimeEventPayload.RunLifecycleChanged
                    if (lifecycle?.status == RuntimeRunStatus.Failed) {
                        throw TimelineTransportHttpException(
                            502,
                            "App Server turn failed: ${lifecycle.reason ?: "unknown"}",
                        )
                    }
                    draft.toLettaMessages(
                        agentId = agentId,
                        conversationId = conversationId,
                        turnId = turnId,
                        fallbackRunId = syntheticRunId,
                    ).forEach { emit(it) }
                }
            } finally {
                activeSendConversations.remove(conversationId)
            }
        }
    }

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> {
        val agentId = runCatching { agentIdFor(conversationId) }.getOrDefault("")
        val frames = flow {
            client.events.collect { received ->
                observedStreamMessages(received, conversationId, agentId).forEach {
                    emit(TimelineStreamFrame.Message(it))
                }
            }
        }
        val heartbeats = flow<TimelineStreamFrame> {
            while (true) {
                delay(heartbeatIntervalMs.milliseconds)
                if (!client.isConnected.first()) break
                emit(TimelineStreamFrame.Heartbeat)
            }
        }
        return merge(frames, heartbeats)
    }

    /**
     * Per-frame projection for [streamConversation]'s passive observer loop:
     * raw [AppServerReceivedFrame] -> (filtered to this conversation) ->
     * RuntimeEventDraft(s) via [runtimeEventMapper] -> [LettaMessage]s via the
     * SAME ServerFrame/WsFrameMapper chain [toLettaMessages] uses for the
     * initiator send path, so ids/otids/prefixes stay byte-identical.
     */
    private fun observedStreamMessages(
        received: AppServerReceivedFrame,
        conversationId: String,
        fallbackAgentId: String,
    ): List<LettaMessage> {
        val streamDelta = received.frame as? AppServerInboundFrame.StreamDelta
            ?: return emptyList()
        if (streamDelta.runtime.conversationId != conversationId) return emptyList()
        if (conversationId in activeSendConversations) return emptyList()
        val effectiveAgentId = streamDelta.runtime.agentId.ifBlank { fallbackAgentId }
        val command = streamObserverCommand(effectiveAgentId, conversationId)
        return runtimeEventMapper.map(command, received).flatMap { draft ->
            RuntimeEventServerFrameMapper.map(
                payload = draft.payload,
                context = RuntimeEventServerFrameMapper.Context(
                    agentId = draft.agentId?.value ?: effectiveAgentId,
                    conversationId = draft.conversationId?.value ?: conversationId,
                    turnId = "desktop-stream-turn-$conversationId",
                    runId = draft.runId?.value ?: "desktop-stream-run-$conversationId",
                ),
            ).mapNotNull(WsFrameMapper::toLettaMessage)
        }
    }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = httpGateway.listConversationMessages(conversationId, limit, after, order)

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> = httpGateway.listAgentMessages(agentId, limit, order, conversationId)

    override suspend fun listConversations(
        limit: Int,
        archiveStatus: String?,
    ): List<Conversation> = httpGateway.listConversations(limit, archiveStatus)
        .also { conversations ->
            conversations.forEach { agentIdByConversation[it.id.value] = it.agentId.value }
        }

    override suspend fun getConversation(conversationId: String): Conversation =
        httpGateway.getConversation(conversationId)
            .also { agentIdByConversation[it.id.value] = it.agentId.value }

    override suspend fun deleteConversation(conversationId: String) {
        httpGateway.deleteConversation(conversationId)
        agentIdByConversation.remove(conversationId)
    }

    override fun close() {
        httpGateway.close()
        transportResources?.close()
    }

    /**
     * Synthetic [TurnCommand] fed to [AppServerRuntimeEventMapper] for passively
     * observed frames — mirrors [IrohChannelTransport.observerTurnCommand]. The
     * command's ids are only fallback context; the wire envelope's own
     * agent/conversation/run ids win (see [RuntimeEventDraft.agentId] etc. above).
     */
    private fun streamObserverCommand(agentId: String, conversationId: String): TurnCommand =
        TurnCommand(
            backendId = BackendId(APP_SERVER_BACKEND_ID),
            runtimeId = RuntimeId("$APP_SERVER_BACKEND_ID:$conversationId"),
            agentId = com.letta.mobile.data.model.AgentId(agentId),
            conversationId = ConversationId(conversationId),
            input = TurnInput.UserMessage(
                localMessageId = "desktop-stream-observer-$conversationId",
                text = "",
            ),
        )

    private suspend fun agentIdFor(conversationId: String): String =
        agentIdByConversation[conversationId] ?: agentIdResolver(conversationId)
            .also { agentIdByConversation[conversationId] = it }

    private fun RuntimeEventDraft.toLettaMessages(
        agentId: String,
        conversationId: String,
        turnId: String,
        fallbackRunId: String,
    ): List<LettaMessage> = RuntimeEventServerFrameMapper.map(
        payload = payload,
        context = RuntimeEventServerFrameMapper.Context(
            agentId = agentId,
            conversationId = conversationId,
            turnId = turnId,
            runId = runId?.value?.takeIf { it.isNotBlank() } ?: fallbackRunId,
        ),
    ).mapNotNull(WsFrameMapper::toLettaMessage)

    private companion object {
        const val APP_SERVER_BACKEND_ID = "desktop-app-server"
    }
}
