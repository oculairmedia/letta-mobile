package com.letta.mobile.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.letta.mobile.data.model.Agent

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    val model: String? = null,
    val embedding: String? = null,
    val agentType: String? = null,
    val enableSleeptime: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val tagsJson: String? = null,
    val toolCount: Int = 0,
    val blockCount: Int = 0,
) {
    fun toAgent() = Agent(
        id = id,
        name = name,
        description = description,
        model = model,
        embedding = embedding,
        agentType = agentType,
        enableSleeptime = enableSleeptime,
        createdAt = createdAt,
        updatedAt = updatedAt,
        tags = tagsJson?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
    )

    companion object {
        fun fromAgent(agent: Agent) = AgentEntity(
            id = agent.id,
            name = agent.name,
            description = agent.description,
            model = agent.model,
            embedding = agent.embedding,
            agentType = agent.agentType,
            enableSleeptime = agent.enableSleeptime,
            createdAt = agent.createdAt,
            updatedAt = agent.updatedAt,
            tagsJson = agent.tags.joinToString(","),
            toolCount = agent.tools.size,
            blockCount = agent.blocks.size,
        )
    }
}
