package com.letta.mobile.runtime.local

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.TurnCommand
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

@Singleton
class AndroidLettaCodeHeadlessClient @Inject constructor(
    private val runtimeController: LettaCodeRuntimeController,
    private val streamJsonMapper: LettaCodeStreamJsonMapper,
    private val localBackendStore: LettaCodeLocalBackendStore,
) : LettaCodeHeadlessClient {
    override fun runTurn(command: TurnCommand, config: LettaConfig): Flow<RuntimeEventDraft> = flow {
        // The local stream announces tool calls (approval_request_message)
        // but never their returns — letta.js only persists results to the
        // transcript. Track this turn's call ids and attach the stored
        // results before the terminal event so the UI's tool chips resolve
        // instead of spinning forever (letta-mobile-bm6x2).
        val pendingToolCallIds = linkedSetOf<String>()
        runtimeController.submit(command, config).collect { line ->
            streamJsonMapper.mapLine(line, command).forEach { draft ->
                when (val payload = draft.payload) {
                    is RuntimeEventPayload.ToolCallObserved ->
                        pendingToolCallIds += payload.toolCallId.value
                    is RuntimeEventPayload.ToolReturnObserved ->
                        pendingToolCallIds -= payload.toolCallId.value
                    is RuntimeEventPayload.RunLifecycleChanged ->
                        if (payload.status.isTerminal) {
                            if (pendingToolCallIds.isNotEmpty()) {
                                emitStoredToolReturns(command, pendingToolCallIds)
                            }
                            localBackendStore.stripPersistedImagePayloads(command.agentId.value)
                        }
                    else -> Unit
                }
                emit(draft)
            }
        }
    }

    private suspend fun FlowCollector<RuntimeEventDraft>.emitStoredToolReturns(
        command: TurnCommand,
        pendingToolCallIds: MutableSet<String>,
    ) {
        val stored = runCatching { localBackendStore.readToolResults(command.agentId.value) }
            .getOrDefault(emptyMap())
        val resolved = pendingToolCallIds.mapNotNull { stored[it] }
        resolved.forEach { result ->
            pendingToolCallIds -= result.toolCallId
            emit(
                RuntimeEventDraft(
                    backendId = command.backendId,
                    runtimeId = command.runtimeId,
                    agentId = command.agentId,
                    conversationId = command.conversationId,
                    source = RuntimeEventSource.LocalRuntime,
                    payload = RuntimeEventPayload.ToolReturnObserved(
                        toolCallId = ToolCallId(result.toolCallId),
                        status = if (result.isError) ToolExecutionStatus.Failed else ToolExecutionStatus.Succeeded,
                        body = result.body,
                    ),
                )
            )
        }
    }
}

private val RuntimeRunStatus.isTerminal: Boolean
    get() = when (this) {
        RuntimeRunStatus.Completed,
        RuntimeRunStatus.Failed,
        RuntimeRunStatus.Cancelled,
        -> true
        RuntimeRunStatus.Started,
        RuntimeRunStatus.Running,
        -> false
    }
