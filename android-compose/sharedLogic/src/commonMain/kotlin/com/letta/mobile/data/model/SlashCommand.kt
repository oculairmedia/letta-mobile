package com.letta.mobile.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SlashCommand(
    val name: String,
    val command: String,
    val description: String = "",
    @SerialName("skill_name") val skillName: String? = null,
    val source: String = "",
    val installed: Boolean = false,
)

@Serializable
data class SlashCommandsResponse(
    val commands: List<SlashCommand> = emptyList(),
)
