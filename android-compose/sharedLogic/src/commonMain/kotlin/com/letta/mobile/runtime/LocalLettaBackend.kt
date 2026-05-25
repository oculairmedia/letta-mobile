package com.letta.mobile.runtime

import com.letta.mobile.data.model.AgentId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LocalLettaBackend(
    override val descriptor: BackendDescriptor,
    private val engine: TurnEngine,
    private val outbox: RuntimeEventOutbox,
    private val memFsStore: MemFsStore,
) : LettaBackend {
    init {
        require(descriptor.kind == BackendKind.LocalKoog || descriptor.kind == BackendKind.CompatibleRuntime) {
            "LocalLettaBackend requires a local or compatible backend descriptor."
        }
    }

    override fun runTurn(command: TurnCommand): Flow<RuntimeEventEnvelope> = flow {
        require(command.backendId == descriptor.backendId) {
            "Command backend ${command.backendId} does not match ${descriptor.backendId}."
        }
        require(command.runtimeId == descriptor.runtimeId) {
            "Command runtime ${command.runtimeId} does not match ${descriptor.runtimeId}."
        }

        command.input.toUserInputDraft(command)?.let { draft ->
            emit(outbox.append(draft))
        }
        emit(outbox.append(command.runStartedDraft()))

        engine.runTurn(command).collect { draft ->
            emit(outbox.append(draft.scopedTo(command)))
        }
    }

    override fun events(afterOffset: RuntimeEventOffset): Flow<RuntimeEventEnvelope> =
        outbox.events(afterOffset)

    suspend fun initializeMemFs(
        agentId: AgentId?,
        writes: List<MemFsWriteCommand>,
    ): List<RuntimeEventEnvelope> = writes.map { write ->
        val commit = memFsStore.write(write)
        outbox.append(
            RuntimeEventDraft(
                backendId = descriptor.backendId,
                runtimeId = descriptor.runtimeId,
                agentId = agentId,
                source = RuntimeEventSource.System,
                payload = RuntimeEventPayload.MemFsCommitObserved(commit),
            ),
        )
    }

    private fun TurnInput.toUserInputDraft(command: TurnCommand): RuntimeEventDraft? = when (this) {
        is TurnInput.UserMessage -> RuntimeEventDraft(
            backendId = descriptor.backendId,
            runtimeId = descriptor.runtimeId,
            agentId = command.agentId,
            conversationId = command.conversationId,
            source = RuntimeEventSource.LocalUser,
            payload = RuntimeEventPayload.LocalUserAppend(
                localMessageId = localMessageId,
                text = text,
            ),
        )

        is TurnInput.ToolApprovalResponse -> RuntimeEventDraft(
            backendId = descriptor.backendId,
            runtimeId = descriptor.runtimeId,
            agentId = command.agentId,
            conversationId = command.conversationId,
            source = RuntimeEventSource.LocalUser,
            payload = RuntimeEventPayload.ApprovalResolved(decision),
        )
    }

    private fun TurnCommand.runStartedDraft(): RuntimeEventDraft = RuntimeEventDraft(
        backendId = descriptor.backendId,
        runtimeId = descriptor.runtimeId,
        agentId = agentId,
        conversationId = conversationId,
        source = RuntimeEventSource.LocalRuntime,
        payload = RuntimeEventPayload.RunLifecycleChanged(RuntimeRunStatus.Started),
    )

    private fun RuntimeEventDraft.scopedTo(command: TurnCommand): RuntimeEventDraft = copy(
        backendId = descriptor.backendId,
        runtimeId = descriptor.runtimeId,
        agentId = agentId ?: command.agentId,
        conversationId = conversationId ?: command.conversationId,
        source = source,
    )
}
