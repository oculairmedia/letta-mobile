package com.letta.mobile.data.pins.shared

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.pins.wire.AgentPinsWire
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The shared list at one [revision]: 0 until the first accepted write, then one more for every
 * accepted write. [agentIds] is in pin order, without duplicates.
 */
@Serializable
data class AgentPinsSnapshot(
    @SerialName("revision") val revision: Long = 0,
    @SerialName(AgentPinsWire.AGENT_IDS) val agentIds: List<AgentId> = emptyList(),
)
