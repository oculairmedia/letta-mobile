package com.letta.mobile.runtime.local

import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnEngine
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow

interface LettaCodeHeadlessClient {
    fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft>
}

@Singleton
class LettaCodeTurnEngine @Inject constructor(
    private val client: LettaCodeHeadlessClient,
) : TurnEngine {
    override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = flow {
        emit(command.runStatus(RuntimeRunStatus.Running))
        client.runTurn(command).collect { draft ->
            emit(draft)
        }
    }.catch { error ->
        emit(
            command.runStatus(
                status = RuntimeRunStatus.Failed,
                reason = error.message ?: "Embedded LettaCode turn failed.",
            )
        )
    }

    private fun TurnCommand.runStatus(
        status: RuntimeRunStatus,
        reason: String? = null,
    ): RuntimeEventDraft = RuntimeEventDraft(
        backendId = backendId,
        runtimeId = runtimeId,
        agentId = agentId,
        conversationId = conversationId,
        source = RuntimeEventSource.LocalRuntime,
        payload = RuntimeEventPayload.RunLifecycleChanged(status = status, reason = reason),
    )
}
