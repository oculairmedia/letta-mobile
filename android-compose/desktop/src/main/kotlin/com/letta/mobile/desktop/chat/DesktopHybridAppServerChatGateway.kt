package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.send.OutboundMessageCreate
import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.iroh.RuntimeEventServerFrameMapper
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Hybrid desktop chat gateway: live chat through the App Server controller,
 * listing/CRUD through HTTP.
 *
 * ROUTING:
 * - [sendConversationMessage]: resolves the conversation's agent, seeds an
 *   Unrestricted runtime (desktop has no approval UI; approvals auto-allow,
 *   matching the Android iroh engine), then drives
 *   [AppServerController.runTurn] and projects the RuntimeEventDraft stream
 *   into LettaMessages via the SAME mappers the Android iroh path uses
 *   (RuntimeEventServerFrameMapper + WsFrameMapper), so ids/otids/prefixes are
 *   byte-identical and the shared timeline reducer dedups correctly.
 * - [streamConversation]: passive view of the App Server stream channel
 *   (stream_delta frames routed by their own runtime.conversation_id), with
 *   synthesized heartbeats so the sync loop's silence timeout doesn't cycle
 *   the subscription while the agent idles (same contract as
 *   IrohAdminRpcChatGateway.streamConversation).
 * - conversation/message listing, agent CRUD, model catalog: HTTP gateway —
 *   the App Server exposes no listing APIs yet.
 *
 * LIFECYCLE: [close] tears down the HTTP gateway and, when this gateway rode
 * an iroh dial, the endpoint+transport pair via [DesktopIrohTransportResources].
 * The controller scope is owned by the factory.
 */
class DesktopHybridAppServerChatGateway internal constructor(
    private val controller: AppServerController,
    private val client: AppServerClient,
    private val httpGateway: DesktopLettaHttpChatGateway,
    private val transportResources: DesktopIrohTransportResources? = null,
    private val heartbeatIntervalMs: Long = IrohAdminRpcChatGateway.STREAM_HEARTBEAT_INTERVAL_MS,
    private val agentIdResolver: suspend (conversationId: String) -> String = { conversationId ->
        httpGateway.getConversation(conversationId).agentId.value
    },
) : DesktopChatGateway, AutoCloseable {

    /** conversationId -> agentId, learned on first send/stream per conversation. */
    private val agentIdByConversation = mutableMapOf<String, String>()

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> {
        val agentId = agentIdFor(conversationId)
        val outbound = OutboundMessageCreate.decode(request)
        controller.startRuntime(
            agentId = com.letta.mobile.data.model.AgentId(agentId),
            conversationId = ConversationId(conversationId),
            mode = AppServerPermissionMode.Unrestricted,
        )
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
            controller.runTurn(command).collect { draft ->
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
        }
    }

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> {
        val agentId = runCatching { agentIdFor(conversationId) }.getOrDefault("")
        val frames = flow {
            client.events.collect { received ->
                val streamDelta = received.frame as? AppServerInboundFrame.StreamDelta
                    ?: return@collect
                if (streamDelta.runtime.conversationId != conversationId) return@collect
                val delta = runCatching { streamDelta.delta.jsonObject }.getOrNull()
                val payload = RuntimeEventPayload.RemoteStreamFrame(
                    frameId = streamDelta.idempotencyKey,
                    messageId = delta?.get("id")?.jsonPrimitive?.contentOrNull,
                    messageType = delta?.get("message_type")?.jsonPrimitive?.contentOrNull,
                    body = received.raw.toString(),
                )
                val runId = delta?.get("run_id")?.jsonPrimitive?.contentOrNull
                    ?: "desktop-stream-$conversationId"
                RuntimeEventServerFrameMapper.map(
                    payload = payload,
                    context = RuntimeEventServerFrameMapper.Context(
                        agentId = streamDelta.runtime.agentId.ifBlank { agentId },
                        conversationId = conversationId,
                        turnId = "desktop-stream-$runId",
                        runId = runId,
                    ),
                ).forEach { frame ->
                    WsFrameMapper.toLettaMessage(frame)?.let {
                        emit(TimelineStreamFrame.Message(it))
                    }
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
