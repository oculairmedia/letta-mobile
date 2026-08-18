package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.send.OutboundMessageCreate
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
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
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnEngine
import com.letta.mobile.runtime.TurnInput
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.JvmInline
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

/** Typed desktop turn identifier. */
@JvmInline
@Serializable
value class DesktopTurnId(val value: String) {
    override fun toString(): String = value
}

/** Typed desktop run identifier. */
@JvmInline
@Serializable
value class DesktopRunId(val value: String) {
    override fun toString(): String = value
}

/** Typed local message identifier. */
@JvmInline
@Serializable
value class LocalMessageId(val value: String) {
    override fun toString(): String = value
}

/** Typed working directory path. */
@JvmInline
@Serializable
value class WorkingDirectoryPath(val value: String) {
    override fun toString(): String = value
}

/** Typed approval request identifier. */
@JvmInline
@Serializable
value class ApprovalRequestId(val value: String) {
    override fun toString(): String = value
}

/**
 * Hybrid desktop chat gateway: live chat through the App Server TurnEngine,
 * listing/CRUD through HTTP.
 */
class DesktopHybridAppServerChatGateway internal constructor(
    private val turnEngine: TurnEngine,
    private val client: AppServerClient,
    private val adminGateway: DesktopAdminChatGateway,
    private val transportResources: DesktopTransportResources? = null,
    private val onClose: (() -> Unit)? = null,
    private val heartbeatIntervalMs: Long = IrohAdminRpcChatGateway.STREAM_HEARTBEAT_INTERVAL_MS,
    private val agentIdResolver: suspend (conversationId: String) -> String = { conversationId ->
        adminGateway.getConversation(conversationId).agentId.value
    },
) : DesktopChatGateway,
    ChatGatewayExtras by adminGateway,
    DesktopApprovalSubmitter,
    DesktopTurnAborter,
    DesktopWorkingDirectoryController,
    AutoCloseable {

    override suspend fun currentWorkingDirectory(agentId: String, conversationId: String): String? =
        currentWorkingDirectory(AgentId(agentId), ConversationId(conversationId))?.value

    suspend fun currentWorkingDirectory(agentId: AgentId, conversationId: ConversationId): WorkingDirectoryPath? =
        (turnEngine as? AppServerTurnEngine)?.currentWorkingDirectory(agentId.value, conversationId.value)
            ?.let(::WorkingDirectoryPath)

    override suspend fun setWorkingDirectory(agentId: String, conversationId: String, path: String): Boolean =
        setWorkingDirectory(AgentId(agentId), ConversationId(conversationId), WorkingDirectoryPath(path))

    suspend fun setWorkingDirectory(agentId: AgentId, conversationId: ConversationId, path: WorkingDirectoryPath): Boolean =
        (turnEngine as? AppServerTurnEngine)?.setWorkingDirectory(agentId.value, conversationId.value, path.value) ?: false

    private val activeRunIdByConversation = ConcurrentHashMap<ConversationId, DesktopRunId>()

    override suspend fun abortConversationTurn(conversationId: String): Boolean =
        abortConversationTurn(ConversationId(conversationId))

    suspend fun abortConversationTurn(conversationId: ConversationId): Boolean {
        val engine = turnEngine as? AppServerTurnEngine ?: return false
        val runId = activeRunIdByConversation[conversationId]
        val agentId = runCatching { agentIdFor(conversationId) }.getOrNull() ?: return false
        val response = engine.abort(agentId.value, conversationId.value, runId?.value) ?: return false
        return response.success && response.aborted
    }

    override suspend fun submitApproval(submission: DesktopApprovalSubmission) {
        val agentId = AgentId(submission.agentId)
        val conversationId = ConversationId(submission.conversationId)
        val requestId = ApprovalRequestId(submission.requestId)
        val scope = AppServerRuntimeScope(
            agentId = agentId.value,
            conversationId = conversationId.value,
        )
        val answerUpdatedInput =
            if (submission.approve) AskUserQuestion.decodeAnswerReason(submission.reason) else null
        val appServerEngine = turnEngine as? AppServerTurnEngine
        val capturedRequestId = submission.toolCallId?.let { appServerEngine?.userInputApprovalId(it) }
        val effectiveRequestId = capturedRequestId ?: requestId.value
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

    private val agentIdByConversation = ConcurrentHashMap<ConversationId, AgentId>()
    private val activeSendConversations = ConcurrentHashMap.newKeySet<ConversationId>()
    private val runtimeEventMapper = AppServerRuntimeEventMapper()

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> = sendConversationMessage(ConversationId(conversationId), request)

    suspend fun sendConversationMessage(
        conversationId: ConversationId,
        request: MessageCreateRequest,
    ): Flow<LettaMessage> {
        val agentId = agentIdFor(conversationId)
        val outbound = OutboundMessageCreate.decode(request)
        val turnId = DesktopTurnId("desktop-turn-${UUID.randomUUID()}")
        val syntheticRunId = DesktopRunId("desktop-run-${UUID.randomUUID()}")
        val localMsgId = outbound.otid?.let(::LocalMessageId)
            ?: LocalMessageId("desktop-local-${UUID.randomUUID()}")
        val command = TurnCommand(
            backendId = BackendId(APP_SERVER_BACKEND_ID),
            runtimeId = RuntimeId("$APP_SERVER_BACKEND_ID:${conversationId.value}"),
            agentId = agentId,
            conversationId = com.letta.mobile.runtime.ConversationId(conversationId.value),
            input = TurnInput.UserMessage(
                localMessageId = localMsgId.value,
                text = outbound.text,
                contentPartsJson = outbound.contentParts?.toString(),
            ),
        )
        return flow {
            activeSendConversations.add(conversationId)
            var deliveredAssistantContent = false
            var mainReplyCompleted = false
            try {
                turnEngine.runTurn(command).collect { draft ->
                    draft.runId?.value?.takeIf { it.isNotBlank() }?.let {
                        activeRunIdByConversation[conversationId] = DesktopRunId(it)
                    }
                    val lifecycle = draft.payload as? RuntimeEventPayload.RunLifecycleChanged
                    if (lifecycle?.status == RuntimeRunStatus.Failed) {
                        val notice = TurnFailureNotices.forFailedTerminal(
                            reason = lifecycle.reason,
                            deliveredAssistantContent = deliveredAssistantContent,
                            mainReplyCompleted = mainReplyCompleted,
                        ) ?: return@collect
                        emit(
                            ErrorMessage(
                                id = "turn-failed-${draft.runId?.value ?: turnId.value}",
                                contentRaw = JsonPrimitive(notice.message),
                                code = notice.kind,
                                runId = draft.runId?.value ?: syntheticRunId.value,
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

    override suspend fun streamConversation(conversationId: String): Flow<TimelineStreamFrame> =
        streamConversation(ConversationId(conversationId))

    suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> {
        val agentId = runCatching { agentIdFor(conversationId) }.getOrDefault(AgentId(""))
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

    private fun observedStreamMessages(
        received: AppServerReceivedFrame,
        conversationId: ConversationId,
        fallbackAgentId: AgentId,
    ): List<LettaMessage> {
        val streamDelta = received.frame as? AppServerInboundFrame.StreamDelta
            ?: return emptyList()
        if (streamDelta.runtime.conversationId != conversationId.value) return emptyList()
        if (conversationId in activeSendConversations) return emptyList()
        val effectiveAgentId = if (streamDelta.runtime.agentId.isNotBlank()) {
            AgentId(streamDelta.runtime.agentId)
        } else {
            fallbackAgentId
        }
        val command = streamObserverCommand(effectiveAgentId, conversationId)
        return runtimeEventMapper.map(command, received).flatMap { draft ->
            RuntimeEventServerFrameMapper.map(
                payload = draft.payload,
                context = RuntimeEventServerFrameMapper.Context(
                    agentId = draft.agentId?.value ?: effectiveAgentId.value,
                    conversationId = draft.conversationId?.value ?: conversationId.value,
                    turnId = "desktop-stream-turn-${conversationId.value}",
                    runId = draft.runId?.value ?: "desktop-stream-run-${conversationId.value}",
                ),
            ).mapNotNull(WsFrameMapper::toLettaMessage)
        }
    }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = adminGateway.listConversationMessages(conversationId, limit, after, order)

    override suspend fun listAgentMessages(
        agentId: String,
        limit: Int?,
        order: String?,
        conversationId: String?,
    ): List<LettaMessage> = adminGateway.listAgentMessages(agentId, limit, order, conversationId)

    override suspend fun listConversations(
        limit: Int,
        archiveStatus: String?,
    ): List<Conversation> = adminGateway.listConversations(limit, archiveStatus)
        .also { conversations ->
            conversations.forEach { agentIdByConversation[it.id] = it.agentId }
        }

    override suspend fun getConversation(conversationId: String): Conversation =
        adminGateway.getConversation(conversationId)
            .also { agentIdByConversation[it.id] = it.agentId }

    override suspend fun deleteConversation(conversationId: String) {
        adminGateway.deleteConversation(conversationId)
        agentIdByConversation.remove(ConversationId(conversationId))
    }

    override fun close() {
        onClose?.invoke()
        adminGateway.close()
        transportResources?.close()
    }

    private fun streamObserverCommand(agentId: AgentId, conversationId: ConversationId): TurnCommand =
        TurnCommand(
            backendId = BackendId(APP_SERVER_BACKEND_ID),
            runtimeId = RuntimeId("$APP_SERVER_BACKEND_ID:${conversationId.value}"),
            agentId = agentId,
            conversationId = com.letta.mobile.runtime.ConversationId(conversationId.value),
            input = TurnInput.UserMessage(
                localMessageId = "desktop-stream-observer-${conversationId.value}",
                text = "",
            ),
        )

    private suspend fun agentIdFor(conversationId: ConversationId): AgentId =
        agentIdByConversation[conversationId] ?: AgentId(agentIdResolver(conversationId.value))
            .also { agentIdByConversation[conversationId] = it }

    private fun RuntimeEventDraft.toLettaMessages(
        agentId: AgentId,
        conversationId: ConversationId,
        turnId: DesktopTurnId,
        fallbackRunId: DesktopRunId,
    ): List<LettaMessage> = RuntimeEventServerFrameMapper.map(
        payload = payload,
        context = RuntimeEventServerFrameMapper.Context(
            agentId = agentId.value,
            conversationId = conversationId.value,
            turnId = turnId.value,
            runId = runId?.value?.takeIf { it.isNotBlank() } ?: fallbackRunId.value,
        ),
    ).mapNotNull(WsFrameMapper::toLettaMessage)

    private companion object {
        const val APP_SERVER_BACKEND_ID = "desktop-app-server"
    }
}

internal class DesktopRuntimeOwnedChatGateway(
    private val delegate: DesktopChatGateway,
    private val runtimeLease: com.letta.mobile.desktop.runtime.DesktopLocalRuntimeLease,
) : DesktopChatGateway by delegate,
    DesktopApprovalSubmitter,
    DesktopTurnAborter,
    DesktopWorkingDirectoryController,
    ChatGatewayExtras,
    AutoCloseable {
    override suspend fun submitApproval(submission: DesktopApprovalSubmission) {
        (delegate as? DesktopApprovalSubmitter)?.submitApproval(submission)
            ?: error("The local App Server gateway cannot submit approvals")
    }

    override suspend fun abortConversationTurn(conversationId: String): Boolean =
        (delegate as? DesktopTurnAborter)?.abortConversationTurn(conversationId) ?: false

    override suspend fun currentWorkingDirectory(agentId: String, conversationId: String): String? =
        (delegate as? DesktopWorkingDirectoryController)?.currentWorkingDirectory(agentId, conversationId)

    override suspend fun setWorkingDirectory(agentId: String, conversationId: String, path: String): Boolean =
        (delegate as? DesktopWorkingDirectoryController)?.setWorkingDirectory(agentId, conversationId, path) ?: false

    override suspend fun createConversation(agentId: String, summary: String?): Conversation =
        (delegate as? ChatGatewayExtras)?.createConversation(agentId, summary)
            ?: error("The local App Server gateway cannot create conversations")

    override suspend fun createAgent(params: com.letta.mobile.data.model.AgentCreateParams): com.letta.mobile.data.model.Agent =
        (delegate as? ChatGatewayExtras)?.createAgent(params)
            ?: error("The local App Server gateway cannot create agents")

    override suspend fun listLlmModels(): List<com.letta.mobile.data.model.LlmModel> =
        (delegate as? ChatGatewayExtras)?.listLlmModels()
            ?: emptyList()

    override suspend fun setConversationModel(conversationId: String, model: String): Conversation =
        (delegate as? ChatGatewayExtras)?.setConversationModel(conversationId, model)
            ?: error("The local App Server gateway cannot update conversation models")

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation =
        (delegate as? ChatGatewayExtras)?.setConversationArchived(conversationId, archived)
            ?: error("The local App Server gateway cannot archive conversations")

    override fun close() {
        try {
            (delegate as? AutoCloseable)?.close()
        } finally {
            runtimeLease.close()
        }
    }
}
