package com.letta.mobile.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["agentId"]),
        Index(value = ["lastMessageAt"]),
        Index(value = ["createdAt"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val agentId: String,
    val summary: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val lastMessageAt: String? = null,
    val archived: Boolean? = null,
    val archivedAt: String? = null,
    val inContextMessageIdsJson: String = "[]",
    val isolatedBlockIdsJson: String = "[]",
    val cachedAtEpochMs: Long = System.currentTimeMillis(),
) {
    fun toConversation() = Conversation(
        id = ConversationId(id),
        agentId = AgentId(agentId),
        summary = summary,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastMessageAt = lastMessageAt,
        archived = archived,
        archivedAt = archivedAt,
        inContextMessageIds = decodeStringList(inContextMessageIdsJson),
        isolatedBlockIds = decodeStringList(isolatedBlockIdsJson),
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val stringListSerializer = ListSerializer(String.serializer())

        fun fromConversation(conversation: Conversation, cachedAtEpochMs: Long = System.currentTimeMillis()) = ConversationEntity(
            id = conversation.id.value,
            agentId = conversation.agentId.value,
            summary = conversation.summary,
            createdAt = conversation.createdAt,
            updatedAt = conversation.updatedAt,
            lastMessageAt = conversation.lastMessageAt,
            archived = conversation.archived,
            archivedAt = conversation.archivedAt,
            inContextMessageIdsJson = encodeStringList(conversation.inContextMessageIds),
            isolatedBlockIdsJson = encodeStringList(conversation.isolatedBlockIds),
            cachedAtEpochMs = cachedAtEpochMs,
        )

        fun encodeStringList(values: List<String>): String = json.encodeToString(stringListSerializer, values)

        fun decodeStringList(rawValues: String?): List<String> {
            val raw = rawValues?.takeIf { it.isNotBlank() } ?: return emptyList()
            return try {
                json.decodeFromString(stringListSerializer, raw)
            } catch (_: SerializationException) {
                emptyList()
            } catch (_: IllegalArgumentException) {
                emptyList()
            }
        }
    }
}

@Entity(tableName = "conversation_refresh_state")
data class ConversationRefreshEntity(
    @PrimaryKey val agentId: String,
    val lastRefreshAtMillis: Long,
)
