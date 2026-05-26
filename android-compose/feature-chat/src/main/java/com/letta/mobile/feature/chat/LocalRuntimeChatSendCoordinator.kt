package com.letta.mobile.feature.chat

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ErrorMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.timeline.api.TimelineExternalTransportWriter
import com.letta.mobile.data.timeline.newOtid
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.LocalLettaBackend
import com.letta.mobile.runtime.RuntimeEventEnvelope
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import com.letta.mobile.util.Telemetry
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** Owns the embedded Kotlin runtime send path. */
internal class LocalRuntimeChatSendCoordinator(
    private val scope: CoroutineScope,
    private val agentId: String,
    private val localBackend: () -> LocalLettaBackend?,
    private val timelineRepository: TimelineExternalTransportWriter,
    private val uiState: MutableStateFlow<ChatUiState>,
    private val clearComposerAfterSend: () -> Unit,
    private val activeConversationId: () -> String?,
    private val setActiveConversationId: (String) -> Unit,
    private val startTimelineObserver: (String) -> Unit,
) {
    private var activeJob: Job? = null
    private var activeConversation: String? = null
    private var activeOtid: String? = null

    fun send(
        text: String,
        attachments: List<MessageContentPart.Image> = emptyList(),
    ): Job {
        val job = scope.launch {
            val timer = Telemetry.startTimer("AdminChatVM", "send.local.enqueue")
            val backend = localBackend()
            if (backend == null) {
                failSend("Local runtime is not available")
                timer.stop("accepted" to false, "reason" to "missing_backend")
                return@launch
            }
            if (attachments.isNotEmpty()) {
                failSend("Local runtime does not support image attachments yet")
                timer.stop("accepted" to false, "reason" to "attachments_unsupported")
                return@launch
            }

            val conversationId = activeConversationId() ?: newLocalConversationId()
            val otid = newOtid()
            activeConversation = conversationId
            activeOtid = otid

            setActiveConversationId(conversationId)
            startTimelineObserver(conversationId)
            timelineRepository.appendExternalTransportLocal(
                conversationId = conversationId,
                content = text,
                otid = otid,
                attachments = attachments,
            )
            clearComposerAfterSend()
            uiState.value = uiState.value.copy(
                conversationState = ConversationState.Ready(conversationId),
                isStreaming = true,
                isAgentTyping = true,
                error = null,
            )

            var terminalStatusSeen = false
            try {
                backend.runTurn(
                    TurnCommand(
                        backendId = backend.descriptor.backendId,
                        runtimeId = backend.descriptor.runtimeId,
                        agentId = AgentId(agentId),
                        conversationId = ConversationId(conversationId),
                        input = TurnInput.UserMessage(
                            localMessageId = otid,
                            text = text,
                        ),
                    )
                ).collect { event ->
                    if (handleRuntimeEvent(conversationId, otid, event)) {
                        terminalStatusSeen = true
                    }
                }

                if (!terminalStatusSeen) {
                    timelineRepository.markExternalTransportLocalSent(conversationId, otid)
                    finishTurn(error = null)
                }
                timer.stop(
                    "accepted" to true,
                    "conversationId" to conversationId,
                    "otid" to otid,
                )
            } catch (cancelled: CancellationException) {
                timelineRepository.markExternalTransportLocalFailed(conversationId, otid)
                finishTurn(error = null)
                timer.stop("accepted" to true, "cancelled" to true)
                throw cancelled
            } catch (error: Exception) {
                val message = error.message ?: "Local runtime turn failed"
                Telemetry.error("AdminChatVM", "send.local.failed", error)
                timelineRepository.markExternalTransportLocalFailed(conversationId, otid)
                timelineRepository.ingestExternalTransportMessage(
                    conversationId = conversationId,
                    message = localErrorMessage(message),
                )
                finishTurn(error = message)
                timer.stopError(error)
            } finally {
                timelineRepository.clearExternalTransportActive(conversationId)
                activeConversation = null
                activeOtid = null
            }
        }
        activeJob = job
        job.invokeOnCompletion {
            if (activeJob === job) {
                activeJob = null
            }
        }
        return job
    }

    fun cancel(): Boolean {
        val job = activeJob ?: return false
        job.cancel()
        finishTurn(error = null)
        return true
    }

    private suspend fun handleRuntimeEvent(
        conversationId: String,
        otid: String,
        event: RuntimeEventEnvelope,
    ): Boolean {
        when (val payload = event.payload) {
            is RuntimeEventPayload.LocalUserAppend -> Unit
            is RuntimeEventPayload.RemoteStreamFrame -> {
                payload.toLettaMessage(event)?.let { message ->
                    timelineRepository.ingestExternalTransportMessage(conversationId, message)
                }
            }
            is RuntimeEventPayload.ExternalTransportFrame -> {
                timelineRepository.ingestExternalTransportMessage(
                    conversationId = conversationId,
                    message = AssistantMessage(
                        id = payload.transportMessageId ?: payload.frameId,
                        contentRaw = JsonPrimitive(payload.body),
                        runId = event.runId?.value,
                    ),
                )
            }
            is RuntimeEventPayload.RunLifecycleChanged -> return handleRunLifecycle(
                conversationId = conversationId,
                otid = otid,
                event = event,
                payload = payload,
            )
            is RuntimeEventPayload.SendMarkedSent -> {
                timelineRepository.markExternalTransportLocalSent(conversationId, payload.localMessageId)
            }
            is RuntimeEventPayload.SendMarkedFailed -> {
                timelineRepository.markExternalTransportLocalFailed(conversationId, payload.localMessageId)
                finishTurn(error = payload.reason)
                return true
            }
            is RuntimeEventPayload.ToolCallObserved,
            is RuntimeEventPayload.ToolReturnObserved,
            is RuntimeEventPayload.ApprovalRequested,
            is RuntimeEventPayload.ApprovalResolved,
            is RuntimeEventPayload.RestSnapshotReconcile,
            is RuntimeEventPayload.RetryRequested,
            is RuntimeEventPayload.MemFsCommitObserved,
            is RuntimeEventPayload.AgentFileImported,
            is RuntimeEventPayload.AgentFileExported,
            -> Unit
        }
        return false
    }

    private suspend fun handleRunLifecycle(
        conversationId: String,
        otid: String,
        event: RuntimeEventEnvelope,
        payload: RuntimeEventPayload.RunLifecycleChanged,
    ): Boolean {
        when (payload.status) {
            RuntimeRunStatus.Started,
            RuntimeRunStatus.Running,
            -> uiState.value = uiState.value.copy(
                conversationState = ConversationState.Ready(conversationId),
                isStreaming = true,
                isAgentTyping = true,
                error = null,
            )
            RuntimeRunStatus.Completed -> {
                timelineRepository.markExternalTransportLocalSent(conversationId, otid)
                finishTurn(error = null)
                return true
            }
            RuntimeRunStatus.Failed -> {
                val reason = payload.reason ?: "Local runtime turn failed"
                timelineRepository.markExternalTransportLocalFailed(conversationId, otid)
                timelineRepository.ingestExternalTransportMessage(
                    conversationId = conversationId,
                    message = localErrorMessage(reason, runId = event.runId?.value),
                )
                finishTurn(error = reason)
                return true
            }
            RuntimeRunStatus.Cancelled -> {
                timelineRepository.markExternalTransportLocalFailed(conversationId, otid)
                finishTurn(error = null)
                return true
            }
        }
        return false
    }

    private fun RuntimeEventPayload.RemoteStreamFrame.toLettaMessage(
        event: RuntimeEventEnvelope,
    ): LettaMessage? {
        if (body.isBlank()) return null
        val id = messageId ?: frameId
        val runId = event.runId?.value
        return when (messageType) {
            "reasoning_message", "reasoning" -> ReasoningMessage(
                id = id,
                reasoning = body,
                runId = runId,
            )
            "system_message", "system" -> SystemMessage(
                id = id,
                contentRaw = JsonPrimitive(body),
                runId = runId,
            )
            "error_message", "error" -> localErrorMessage(body, id = id, runId = runId)
            else -> AssistantMessage(
                id = id,
                contentRaw = JsonPrimitive(body),
                runId = runId,
                messageType = messageType ?: "assistant_message",
            )
        }
    }

    private fun failSend(message: String) {
        uiState.value = uiState.value.copy(
            error = message,
            isStreaming = false,
            isAgentTyping = false,
        )
    }

    private fun finishTurn(error: String?) {
        uiState.value = uiState.value.copy(
            isStreaming = false,
            isAgentTyping = false,
            error = error,
        )
    }

    private fun newLocalConversationId(): String = "local-conv-$agentId-${UUID.randomUUID()}"

    private fun localErrorMessage(
        message: String,
        id: String = "local-error-${UUID.randomUUID()}",
        runId: String? = null,
    ): ErrorMessage = ErrorMessage(
        id = id,
        contentRaw = JsonPrimitive(message),
        runId = runId,
    )
}
