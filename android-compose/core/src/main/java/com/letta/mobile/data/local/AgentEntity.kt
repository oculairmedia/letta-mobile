package com.letta.mobile.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

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
        id = AgentId(id),
        name = name,
        description = description,
        model = model,
        embedding = embedding,
        agentType = agentType,
        enableSleeptime = enableSleeptime,
        createdAt = createdAt,
        updatedAt = updatedAt,
        tags = decodeTags(tagsJson),
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val tagListSerializer = ListSerializer(String.serializer())

        fun encodeTags(tags: List<String>): String = json.encodeToString(tagListSerializer, tags)

        fun decodeTags(rawTags: String?): List<String> {
            val raw = rawTags?.takeIf { it.isNotBlank() } ?: return emptyList()
            return try {
                json.decodeFromString(tagListSerializer, raw)
            } catch (_: SerializationException) {
                raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
            } catch (_: IllegalArgumentException) {
                raw.split(",").map { it.trim() }.filter { it.isNotBlank() }
            }
        }

        fun isJsonEncodedTags(rawTags: String?): Boolean {
            val raw = rawTags?.trim()?.takeIf { it.isNotEmpty() } ?: return true
            return try {
                json.decodeFromString(tagListSerializer, raw)
                true
            } catch (_: SerializationException) {
                false
            } catch (_: IllegalArgumentException) {
                false
            }
        }

        fun fromAgent(agent: Agent) = AgentEntity(
            id = agent.id.value,
            name = agent.name,
            description = agent.description,
            model = agent.model,
            embedding = agent.embedding,
            agentType = agent.agentType,
            enableSleeptime = agent.enableSleeptime,
            createdAt = agent.createdAt,
            updatedAt = agent.updatedAt,
            tagsJson = encodeTags(agent.tags),
            toolCount = agent.tools.size,
            blockCount = agent.blocks.size,
        )
    }
}
