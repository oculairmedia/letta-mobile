package com.letta.mobile.data.presence

import com.letta.mobile.data.model.Run
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus

/**
 * The App Server mints `local-run-N` ids in memory (they recycle after a restart); the host's run.get reads only
 * the local backend run store (`run-<uuid>`) and refuses them, so looking one up is a refused RPC every tick.
 */
internal fun RunId.isAppServerRunId(): Boolean = value.startsWith(APP_SERVER_RUN_ID_PREFIX)

private const val APP_SERVER_RUN_ID_PREFIX = "local-run-"

internal suspend fun ActiveTurnSession.reconcileIfActive(owner: ActiveTurnCoordinator) {
    val turn = owner.state.value
    if (turn.phase == ActiveTurnPhase.Idle) return
    runCatching { reconcile(turn.conversationId, turn.agentId) }.onFailure {
        // Unavailable reads are not evidence of a terminal.
        if (it is kotlinx.coroutines.CancellationException) throw it
    }
}


internal fun ActiveTurnSession.applyReconciledRuns(captured: ActiveTurnState, server: RunId?, runs: List<Run>) {
    runs.filter { it.matchesScope(captured) }
        .filter { server == null || it.id == server.value }
        .mapNotNull { it.activeTurnEvent(captured) }
        .forEach(::onEvent)
}

private fun Run.matchesScope(captured: ActiveTurnState): Boolean {
    return agentId == captured.agentId.value && conversationId == captured.conversationId.value
}

private fun Run.activeTurnEvent(captured: ActiveTurnState): ActiveTurnRuntimeEvent? {
    val status = when (this.status) {
        "completed" -> RuntimeRunStatus.Completed
        "failed" -> RuntimeRunStatus.Failed
        "cancelled", "canceled" -> RuntimeRunStatus.Cancelled
        "running", "created" -> RuntimeRunStatus.Started
        else -> return null
    }
    return ActiveTurnRuntimeEvent(captured.conversationId, captured.agentId, RuntimeEventPayload.RunLifecycleChanged(status),
        RunId(id), RunId(id))
}
