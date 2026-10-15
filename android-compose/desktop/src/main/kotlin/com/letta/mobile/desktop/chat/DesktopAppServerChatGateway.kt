package com.letta.mobile.desktop.chat

import com.letta.mobile.data.chat.branch.ConversationForkGateway
import com.letta.mobile.data.chat.recompile.ConversationRecompileGateway
import com.letta.mobile.data.chat.recompile.ConversationRecompileRequest
import com.letta.mobile.data.chat.branch.ConversationForkRequest
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.data.chat.runtime.ConversationSummaryGateway
import com.letta.mobile.data.chat.runtime.ConversationSummaryUpdate
import com.letta.mobile.data.chat.send.OutboundMessageCreate
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentCreateParams
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.controller.AppServerApprovalDecisions
import com.letta.mobile.data.controller.ApprovalRejectedException
import com.letta.mobile.data.controller.ApprovalSubmission
import com.letta.mobile.data.controller.ApprovalSubmitResult
import com.letta.mobile.data.controller.withSelectedSuggestions
import com.letta.mobile.data.runtime.approvalIdForSuggestions
import com.letta.mobile.data.repository.appserver.AppServerLocalAdminGateway
import com.letta.mobile.data.repository.iroh.IrohAdminRpcChatGateway
import com.letta.mobile.data.runtime.AppServerRuntimeEventMapper
import com.letta.mobile.data.timeline.TimelineStreamFrame
import com.letta.mobile.data.timeline.TimelineTransportHttpException
import com.letta.mobile.data.transport.WsFrameMapper
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.runtime.ApprovalBinding
import com.letta.mobile.data.runtime.ModeChangeResult
import com.letta.mobile.data.runtime.setPermissionMode
import com.letta.mobile.data.runtime.PermissionModeRegistry
import com.letta.mobile.data.runtime.pendingApprovalDetails
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.letta.mobile.data.chat.runtime.ConnectionStatusGateway
import com.letta.mobile.data.transport.ChannelTransportState
import kotlinx.coroutines.flow.onEach
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
 * The desktop's one chat gateway (letta-mobile-ckmub.4): every turn runs through the App Server
 * [TurnEngine], and every listing / CRUD call goes over the same App Server session through the
 * shared [AppServerLocalAdminGateway]. It serves the bundled local runtime and a remote App Server
 * reached by ws:// or iroh:// alike; there is no Letta REST path behind it.
 *
 * Adding another backend family later (e.g. a cloud backend) means a second implementation of
 * [DesktopChatGateway] chosen by [DesktopAppServerChatGateways]; nothing in the shared shell keys
 * on which one is bound (see `BackendKind`).
 */
class DesktopAppServerChatGateway internal constructor(
    private val turnEngine: TurnEngine,
    private val client: AppServerClient,
    private val admin: AppServerLocalAdminGateway = AppServerLocalAdminGateway(client) { operation ->
        "desktop-local-$operation-${UUID.randomUUID()}"
    },
    private val transportResources: DesktopTransportResources? = null,
    private val onClose: (() -> Unit)? = null,
    override val permissionModes: PermissionModeRegistry? = null,
    private val heartbeatIntervalMs: Long = IrohAdminRpcChatGateway.STREAM_HEARTBEAT_INTERVAL_MS,
    private val agentIdResolver: suspend (conversationId: String) -> String = { conversationId ->
        admin.getConversation(conversationId).agentId.value
    },
    /** letta-mobile-by4vm.9/.56: the session that reconnects itself (ws:// and iroh://); null for test fakes. */
    private val session: DesktopSelfReconnectingSession? = null,
) : DesktopChatGateway,
    ConnectionStatusGateway,
    DesktopReconnectOwningGateway,
    ChatGatewayExtras,
    ConversationSummaryGateway,
    ConversationForkGateway,
    ConversationRecompileGateway,
    DesktopApprovalSubmitter,
    DesktopPendingApprovalSource,
    DesktopPermissionModeController,
    DesktopTurnAborter,
    DesktopWorkingDirectoryController,
    DesktopRuntimeEventSource,
    AutoCloseable {

    /**
     * letta-mobile-by4vm.9: the session status the chat controller's watcher reads. A gateway
     * without a self-reconnecting session reports Idle, which the watcher ignores.
     */
    override val connectionState: StateFlow<ChannelTransportState> =
        session?.connectionState ?: MutableStateFlow(ChannelTransportState.Idle)

    override val ownsReconnect: Boolean get() = session != null

    /** The machine woke from sleep: true when this gateway's own reconnect owner took it (F04). */
    override fun resumeSession(): Boolean {
        val owner = session ?: return false
        owner.resumeNow()
        return true
    }

    /** The bundled runtime this gateway keeps alive, released when the gateway closes. */
    @Volatile
    private var ownedRuntime: AutoCloseable? = null

    /** Ties [runtime]'s lifetime to this gateway: closing the gateway releases it. */
    internal fun owningRuntime(runtime: AutoCloseable): DesktopAppServerChatGateway = apply { ownedRuntime = runtime }

    override val pendingApprovalDetails =
        (turnEngine as? AppServerTurnEngine)?.pendingApprovalDetails ?: kotlinx.coroutines.flow.MutableStateFlow(emptyMap())

    /**
     * The turns' own runtime events, republished for presence. The timeline takes the same drafts
     * as messages; the run-phase reducer takes them as phases, so neither has to guess from the
     * other's projection.
     */
    private val runtimeEventRelay = DesktopRuntimeEventRelay()
    override val runtimeEvents = runtimeEventRelay.runtimeEvents

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

    override suspend fun setPermissionMode(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode): Boolean =
        permissionModes?.change(runtime, mode) { requested ->
            (turnEngine as? AppServerTurnEngine)?.setPermissionMode(runtime, requested) ?: ModeChangeResult.Unconfirmed
        } ?: false

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
        // letta-mobile-bzvro.11: a parked control request that is not a user-input tool is answered
        // against the control request's own id too (the card's id is the streamed message's).
        val capturedRequestId = capturedApprovalId(appServerEngine, submission)
        val effectiveRequestId = effectiveApprovalId(capturedRequestId, submission, requestId.value)
        val decision = AppServerApprovalDecisions.decide(
            approve = submission.approve,
            updatedInput = answerUpdatedInput,
            message = submission.reason,
            defaultApproveMessage = "Approved by desktop client.",
            defaultDenyMessage = "Denied by desktop client.",
        ).withSelectedSuggestions(submission.selectedSuggestionIds)
        if (appServerEngine == null) {
            client.input(
                AppServerCommand.Input(
                    runtime = scope,
                    payload = AppServerInputPayload.ApprovalResponse(
                        requestId = effectiveRequestId,
                        decision = decision,
                    ),
                ),
            )
        } else {
            // letta-mobile-qygvv.5: awaits input_accepted and caches the decision so
            // a server replay is re-answered. A rejection reaches the approval
            // coordinator's error path instead of vanishing.
            val result = appServerEngine.submitApprovalResponse(ApprovalSubmission(scope, effectiveRequestId, decision))
            if (result is ApprovalSubmitResult.Rejected) failRejected(appServerEngine, submission, effectiveRequestId, result.error)
        }
        submission.toolCallId?.let { toolCallId ->
            capturedRequestId?.let { appServerEngine?.clearUserInputApprovalId(ApprovalBinding(toolCallId, it)) }
        }
    }

    /**
     * An always-allow is bound to the exact request its card offered the rule for; the streamed
     * message id ([fallback]) is never a stand-in for it, so a rule can't be persisted against another call.
     */
    private fun effectiveApprovalId(captured: String?, submission: DesktopApprovalSubmission, fallback: String): String {
        if (submission.selectedSuggestionIds.isEmpty()) return captured ?: fallback
        return captured ?: throw ApprovalRejectedException("the permission rule is no longer offered for this request")
    }

    /**
     * The server refused the answer. When it no longer holds the request at all, its parked details are
     * dropped too, so the card stops being live.
     */
    private fun failRejected(engine: AppServerTurnEngine, submission: DesktopApprovalSubmission, approvalId: String, error: String): Nothing {
        if (error.contains("no longer pending", ignoreCase = true)) {
            submission.toolCallId?.let { engine.clearUserInputApprovalId(ApprovalBinding(it, approvalId)) }
        }
        throw ApprovalRejectedException(error)
    }

    /**
     * The real control-request id to answer [submission] with: the registry's gate id, else the parked
     * details'. With suggestion ids it is only the id the card's own details carried, and only while
     * those are still the parked details for that call ([approvalIdForSuggestions]).
     */
    private fun capturedApprovalId(engine: AppServerTurnEngine?, submission: DesktopApprovalSubmission): String? {
        val callId = submission.toolCallId ?: return null
        val parked = engine?.pendingApprovalDetails?.value?.get(callId)
        if (submission.selectedSuggestionIds.isNotEmpty()) {
            return parked.approvalIdForSuggestions(submission.suggestionBinding, submission.selectedSuggestionIds)
        }
        return engine?.userInputApprovalId(callId) ?: parked?.approvalId
    }

    private val agentIdByConversation = ConcurrentHashMap<ConversationId, AgentId>()
    private val activeSendConversations = ConcurrentHashMap.newKeySet<ConversationId>()
    private val runtimeEventMapper = AppServerRuntimeEventMapper()

    override suspend fun sendConversationMessage(
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
                turnEngine.runTurn(command).relayedFor(conversationId, agentId).collect { draft ->
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

    override suspend fun streamConversation(conversationId: ConversationId): Flow<TimelineStreamFrame> {
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

    /** Every runtime event this gateway maps is republished for presence, whichever path produced it. */
    private fun Flow<RuntimeEventDraft>.relayedFor(conversationId: ConversationId, agentId: AgentId): Flow<RuntimeEventDraft> =
        onEach { draft -> runtimeEventRelay.emit(conversationId.value, agentId.value, draft.payload) }

    /** An observed draft is scoped to what it names, else to the stream it arrived on. */
    private suspend fun relayObserved(draft: RuntimeEventDraft, conversationId: ConversationId, agentId: AgentId) {
        runtimeEventRelay.emit(
            draft.conversationId?.value ?: conversationId.value,
            draft.agentId?.value ?: agentId.value,
            draft.payload,
        )
    }

    // Suspending only for the relay: an externally started or background turn reaches the timeline
    // through this path, and its tool / approval phases have to reach presence the same way.
    private suspend fun observedStreamMessages(
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
            relayObserved(draft, conversationId, effectiveAgentId)
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
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = admin.listConversationMessages(conversationId.value, limit, after, order)

    override suspend fun listConversationMessagesBefore(
        conversationId: ConversationId,
        limit: Int,
        before: String,
        order: String,
    ): List<LettaMessage> = admin.listConversationMessagesBefore(conversationId.value, limit, before, order)

    /** The App Server reads messages per conversation; an agent-wide read would fan out, so it is refused. */
    override suspend fun listAgentMessages(
        agentId: AgentId,
        limit: Int?,
        order: String?,
        conversationId: ConversationId?,
    ): List<LettaMessage> = admin.listConversationMessages(
        requireNotNull(conversationId?.value) { "App Server message reads require conversationId?.value" },
        limit,
        after = null,
        order = order,
    )

    override suspend fun listConversations(
        limit: Int,
        archiveStatus: String?,
    ): List<Conversation> = admin.listConversations(limit, archiveStatus)
        .also { conversations ->
            conversations.forEach { agentIdByConversation[it.id] = it.agentId }
        }

    override suspend fun getConversation(conversationId: String): Conversation =
        admin.getConversation(conversationId)
            .also { agentIdByConversation[it.id] = it.agentId }

    override suspend fun createConversation(agentId: String, summary: String?): Conversation =
        admin.createConversation(agentId, summary)
            .also { agentIdByConversation[it.id] = it.agentId }

    override suspend fun setConversationModel(conversationId: String, model: String): Conversation =
        admin.setConversationModel(conversationId, model)

    override suspend fun setConversationArchived(conversationId: String, archived: Boolean): Conversation =
        admin.setConversationArchived(conversationId, archived)

    override suspend fun createAgent(params: AgentCreateParams): Agent = admin.createAgent(params)

    override suspend fun listLlmModels(): List<LlmModel> = admin.listLlmModels()

    /**
     * letta-mobile-bzvro.17: the App Server has no `conversation_delete`, so a delete archives and
     * hides the conversation; it leaves every list (Archived too) and stays on disk, but the app's
     * only way back is the undo offered right after.
     */
    override suspend fun deleteConversation(conversationId: String) {
        admin.setConversationRemoved(ConversationId(conversationId), removed = true)
        // Its stored mode goes with it (when the owning agent is known from an earlier send or read).
        agentIdByConversation.remove(ConversationId(conversationId))
            ?.let { permissionModes?.forget(AppServerRuntimeScope(it.value, conversationId)) }
    }

    override val deleteBehavior: ConversationDeleteBehavior get() = ConversationDeleteBehavior.RemovesFromLists

    /** The undo of [deleteConversation]: un-hide, and archived again only if it was archived before. */
    override suspend fun restoreDeletedConversation(conversationId: String, wasArchived: Boolean) {
        admin.restoreRemovedConversation(ConversationId(conversationId), archived = wasArchived)
    }

    /** letta-mobile-bzvro.17: rename (and the generated title) is the conversation's `summary`. */
    override suspend fun setConversationSummary(update: ConversationSummaryUpdate): Conversation =
        admin.renameConversation(update)

    /** letta-mobile-bzvro.15: `conversation_fork` through the App Server. */
    override suspend fun forkConversation(request: ConversationForkRequest): Conversation =
        admin.forkConversation(request)
            .also { agentIdByConversation[it.id] = it.agentId }

    /** letta-mobile-zxk2n.5: `/recompile` as `conversation_recompile` through the App Server. */
    override suspend fun recompileConversation(request: ConversationRecompileRequest) =
        admin.recompileConversation(request)

    override fun close() {
        try {
            onClose?.invoke()
            transportResources?.close()
        } finally {
            ownedRuntime?.close()
        }
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
