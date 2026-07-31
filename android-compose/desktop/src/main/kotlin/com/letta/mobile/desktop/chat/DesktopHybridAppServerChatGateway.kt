package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.send.OutboundMessageCreate
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.controller.AppServerApprovalDecisions
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.runtime.AppServerRuntimeEventMapper
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.TurnFailureNotices
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
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
import kotlinx.serialization.json.JsonPrimitive

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
    private val onClose: (() -> Unit)? = null,
    private val heartbeatIntervalMs: Long = IrohAdminRpcChatGateway.STREAM_HEARTBEAT_INTERVAL_MS,
    private val agentIdResolver: suspend (conversationId: String) -> String = { conversationId ->
        httpGateway.getConversation(conversationId).agentId.value
    },
) : DesktopChatGateway,
    ChatGatewayExtras by httpGateway,
    DesktopApprovalSubmitter,
    DesktopTurnAborter,
    AutoCloseable {

    /**
     * conversationId -> the canonical run id most recently seen on that
     * conversation's in-flight turn. letta-mobile-lgns8.19 addresses the abort at
     * that run so the server tears down the RIGHT run; a null entry still aborts
     * (the server then targets whatever run is active for the runtime).
     */
    private val activeRunIdByConversation = ConcurrentHashMap<String, String>()

    /**
     * letta-mobile-lgns8.19: REAL server-side abort for the desktop stop button.
     * Previously desktop only cancelled its local collect job, so a long tool call
     * ran to completion and its output later surfaced as a ghost resume.
     */
    override suspend fun abortConversationTurn(conversationId: String): Boolean {
        val engine = turnEngine as? AppServerTurnEngine ?: return false
        val runId = activeRunIdByConversation[conversationId]
        // Keyed abort: with concurrent runtimes the keyless overload targets the
        // most recently started scope, which can abort the WRONG conversation.
        val agentId = runCatching { agentIdFor(conversationId) }.getOrNull() ?: return false
        val response = engine.abort(agentId, conversationId, runId) ?: return false
        // A response with success=false or aborted=false means no run was torn
        // down (stale/already-terminal run id): report undelivered so the
        // controller falls back to its local clear instead of holding the
        // "stopping" state for the full terminal timeout.
        return response.success && response.aborted
    }

    /**
     * Answer / dismiss a parked runtime approval (e.g. AskUserQuestion) over the
     * App Server input channel — desktop parity for the mobile approval-submit
     * path. Mirrors [com.letta.mobile.data.controller.DefaultAppServerController.submitApproval]:
     * an AskUserQuestion answer rides the `reason` channel (decoded here into an
     * `updated_input` allow), and interactive tools resolve against letta-code's
     * `perm-call_<callId>` control-request id — NOT the display approval id. The
     * runtime scope is rebuilt from the conversation's agent/conversation ids
     * (single-user desktop backend), matching the scope minted at runtime_start.
     * See letta-mobile-vilsn.8.
     */
    override suspend fun submitApproval(submission: DesktopApprovalSubmission) {
        val scope = AppServerRuntimeScope(
            agentId = submission.agentId,
            conversationId = submission.conversationId,
        )
        val answerUpdatedInput =
            if (submission.approve) AskUserQuestion.decodeAnswerReason(submission.reason) else null
        // letta-mobile-vilsn: answer against the REAL can_use_tool gate id the
        // engine captured when it surfaced the approval (correct across providers:
        // call_… vs toolu_…); the display id and the string heuristic don't match
        // Claude's toolu_ ids. Heuristic remains only as a last-resort fallback.
        val appServerEngine = turnEngine as? AppServerTurnEngine
        val capturedRequestId = submission.toolCallId?.let { appServerEngine?.userInputApprovalId(it) }
        val effectiveRequestId = capturedRequestId ?: submission.requestId
        val decision = AppServerApprovalDecisions.decide(
            approve = submission.approve,
            updatedInput = answerUpdatedInput,
            message = submission.reason,
            defaultApproveMessage = "Approved by desktop client.",
            defaultDenyMessage = "Denied by desktop client.",
        )
        client.input(
            AppServerCommand.Input(
                runtime = scope,
                payload = AppServerInputPayload.ApprovalResponse(
                    requestId = effectiveRequestId,
                    decision = decision,
                ),
            ),
        )
        submission.toolCallId?.let { toolCallId ->
            capturedRequestId?.let { appServerEngine?.clearUserInputApprovalId(toolCallId, it) }
        }
    }

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
            // letta-mobile-br5g0: computed from the turn's OWN observed frames —
            // did this turn actually put assistant content on screen?
            var deliveredAssistantContent = false
            var mainReplyCompleted = false
            try {
                turnEngine.runTurn(command).collect { draft ->
                    // letta-mobile-lgns8.19: remember the turn's canonical run id
                    // so a stop can address the abort at the right run.
                    draft.runId?.value?.takeIf { it.isNotBlank() }?.let {
                        activeRunIdByConversation[conversationId] = it
                    }
                    val lifecycle = draft.payload as? RuntimeEventPayload.RunLifecycleChanged
                    if (lifecycle?.status == RuntimeRunStatus.Failed) {
                        // letta-mobile-br5g0: a Failed terminal AFTER the main reply
                        // completed is a trailing aux-step failure, not a dead turn.
                        // Partial streamed content without a completed stop still
                        // emits a visible ERROR row.
                        val notice = TurnFailureNotices.forFailedTerminal(
                            reason = lifecycle.reason,
                            deliveredAssistantContent = deliveredAssistantContent,
                            mainReplyCompleted = mainReplyCompleted,
                        ) ?: return@collect
                        emit(
                            ErrorMessage(
                                id = "turn-failed-${draft.runId?.value ?: turnId}",
                                contentRaw = JsonPrimitive(notice.message),
                                code = notice.kind,
                                runId = draft.runId?.value ?: syntheticRunId,
                            ),
                        )
                        throw TimelineTransportHttpException(
                            502,
                            "App Server turn failed: ${lifecycle.reason ?: "unknown"}",
                        )
                    }
                    if (marksMainReplyCompleted(draft.payload)) {
                        mainReplyCompleted = true
                    }
                    draft.toLettaMessages(
                        agentId = agentId,
                        conversationId = conversationId,
                        turnId = turnId,
                        fallbackRunId = syntheticRunId,
                    ).forEach { message ->
                        if (message is AssistantMessage && message.content.isNotBlank()) {
                            deliveredAssistantContent = true
                        }
                        emit(message)
                    }
                }
            } finally {
                activeSendConversations.remove(conversationId)
                activeRunIdByConversation.remove(conversationId)
            }
        }
    }

    /**
     * Explicit main-reply completion only — parse failures and intermediate
     * stops (requires_approval / error) return false.
     */
    private fun marksMainReplyCompleted(payload: RuntimeEventPayload): Boolean = when (payload) {
        is RuntimeEventPayload.RemoteStreamFrame -> {
            payload.messageType == "stop_reason" &&
                TurnFailureNotices.isCompletedMainReplyStopReason(
                    TurnFailureNotices.stopReasonFromStreamDeltaBody(payload.body),
                )
        }
        is RuntimeEventPayload.RunLifecycleChanged ->
            payload.status == RuntimeRunStatus.Completed
        else -> false
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
        onClose?.invoke()
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
