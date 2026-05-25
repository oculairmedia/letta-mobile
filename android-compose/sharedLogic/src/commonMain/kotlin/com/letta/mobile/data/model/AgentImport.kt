package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ImportedAgentsResponse(
    @SerialName("agent_ids") val agentIds: List<String> = emptyList(),
)
