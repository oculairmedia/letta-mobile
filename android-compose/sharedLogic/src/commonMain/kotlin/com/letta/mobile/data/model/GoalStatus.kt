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
    @Serializable(with = GoalStatusStateSerializer::class)
    val status: GoalStatusState = GoalStatusState.Unknown(""),
    val createdAt: String = "",
    val updatedAt: String = "",
    val activeStartedAt: String? = null,
    val activeTimeSeconds: Long = 0,
    val tokensUsed: Long = 0,
    val tokenBudget: Long? = null,
)

sealed interface GoalStatusState {
    val wireValue: String

    data object Active : GoalStatusState { override val wireValue = "active" }
    data object Completed : GoalStatusState { override val wireValue = "completed" }
    data object Cancelled : GoalStatusState { override val wireValue = "cancelled" }
    data class Unknown(val raw: String) : GoalStatusState { override val wireValue = raw }

    companion object {
        fun fromWire(value: String): GoalStatusState = when (value) {
            Active.wireValue -> Active
            Completed.wireValue -> Completed
            Cancelled.wireValue -> Cancelled
            else -> Unknown(value)
        }
    }
}

object GoalStatusStateSerializer : kotlinx.serialization.KSerializer<GoalStatusState> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor(
        "GoalStatusState",
        kotlinx.serialization.descriptors.PrimitiveKind.STRING,
    )

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): GoalStatusState =
        GoalStatusState.fromWire(decoder.decodeString())

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: GoalStatusState) {
        encoder.encodeString(value.wireValue)
    }
}
