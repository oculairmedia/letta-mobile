package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GoalStatusResponse(
    val source: String = "",
    @SerialName("server_key") val serverKey: String = "",
    @SerialName("agent_id") val agentId: String = "",
    @SerialName("conversation_id") val conversationId: String = "",
    val goal: GoalStatus? = null,
)

@Serializable
data class GoalStatus(
    val objective: String = "",
    val status: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val activeStartedAt: String? = null,
    val activeTimeSeconds: Long = 0,
    val tokensUsed: Long = 0,
    val tokenBudget: Long? = null,
)
