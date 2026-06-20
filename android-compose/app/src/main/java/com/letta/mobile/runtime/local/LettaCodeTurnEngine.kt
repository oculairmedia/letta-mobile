package com.letta.mobile.runtime.local

import android.util.Log

import com.letta.mobile.data.model.LettaConfig
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
    fun runTurn(command: TurnCommand, config: LettaConfig): Flow<RuntimeEventDraft>
}

class LettaCodeTurnEngine(
    private val client: LettaCodeHeadlessClient,
    private val config: LettaConfig,
) : TurnEngine {
    override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = flow {
        Log.i("LOCAL_HANDOFF", "engine_runTurn_start agent=${command.agentId.value} conversation=${command.conversationId.value} model=${config.localModelHandle} serverUrl=${config.serverUrl}")
        emit(command.runStatus(RuntimeRunStatus.Running))
        Log.i("LOCAL_HANDOFF", "engine_client_collect_start agent=${command.agentId.value} conversation=${command.conversationId.value}")
        client.runTurn(command, config).collect { draft ->
            Log.i("LOCAL_HANDOFF", "engine_client_draft payload=${draft.payload::class.simpleName} source=${draft.source}")
            emit(draft)
        }
        Log.i("LOCAL_HANDOFF", "engine_runTurn_complete agent=${command.agentId.value} conversation=${command.conversationId.value}")
    }.catch { error ->
        Log.e("LOCAL_HANDOFF", "engine_runTurn_failed agent=${command.agentId.value} conversation=${command.conversationId.value}", error)
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

@Singleton
class LettaCodeTurnEngineFactory @Inject constructor(
    private val client: LettaCodeHeadlessClient,
) {
    fun create(config: LettaConfig): TurnEngine = LettaCodeTurnEngine(client, config)
}
