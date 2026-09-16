package com.letta.mobile.data.repository

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.isLettaCodeEphemeralWorker
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport

/**
 * Reacts to `agent_updated` pushes on [transport] (Meridian sends them after every agent write):
 * [onDeleted] for `reason = deleted`, [onChanged] for anything else. Shared by every agent
 * repository so Android and desktop keep their rosters current the same way.
 *
 * Ephemeral letta-code subagents (`agent-local-*` workers) churn in bursts while a run fans out;
 * they are not part of the human agent list, so they get no per-agent fetch and the next bulk
 * refresh reconciles them (letta-mobile-vcmin).
 */
suspend fun observeAgentUpdates(
    transport: IChannelTransport,
    onDeleted: suspend (AgentId) -> Unit,
    onChanged: suspend (AgentId) -> Unit,
) {
    transport.events.collect { frame ->
        if (frame !is ServerFrame.AgentUpdated) return@collect
        val agentId = AgentId(frame.agentId)
        when {
            frame.reason == "deleted" -> onDeleted(agentId)
            agentId.isLettaCodeEphemeralWorker() -> Unit
            else -> onChanged(agentId)
        }
    }
}

/**
 * Calls [refresh] each time [transport] comes back after a disconnect. Pushes sent while a client
 * was offline are lost, so a reconnect must reconcile the whole roster.
 */
suspend fun observeReconnectRefresh(transport: IChannelTransport, refresh: suspend () -> Unit) {
    var wasConnected: Boolean? = null
    transport.state.collect { state ->
        val nowConnected = state is ChannelTransportState.Connected
        if (wasConnected == false && nowConnected) refresh()
        wasConnected = nowConnected
    }
}
