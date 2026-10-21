package com.letta.mobile.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationModelSettings
import com.letta.mobile.data.model.ConversationRecency
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["agentId"]),
        Index(value = ["lastMessageAt"]),
        Index(value = ["createdAt"]),
        Index(value = ["agentId", "lastMessageAtEpochMs"]),
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
    val cachedAtEpochMs: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
    /**
     * The conversation's own model override, its window and effort, so a
     * cold start shows the host's override before the first refresh instead of the agent's model.
     */
    val model: String? = null,
    val contextWindowLimit: Int? = null,
    val modelEffort: String? = null,
    /**
     * The three timestamps above as epoch millis, parsed once at write
     * time by [ConversationRecency]. Lists sort on these, not on the ISO strings, whose mixed
     * formats (`Z` vs `+00:00`, fraction precision) do not sort chronologically as text. The String
     * columns stay for compatibility this release.
     */
    val lastMessageAtEpochMs: Long? = null,
    val updatedAtEpochMs: Long? = null,
    val createdAtEpochMs: Long? = null,
) {
    fun toConversation(): Conversation = Conversation(
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
        model = model,
        modelSettings = modelEffort?.let { ConversationModelSettings(effort = it) },
        contextWindowLimit = contextWindowLimit,
    )

    companion object {
        private val json = LettaJson.persisted
        private val stringListSerializer = ListSerializer(String.serializer())

        fun fromConversation(
            conversation: Conversation,
            cachedAtEpochMs: Long = kotlin.time.Clock.System.now().toEpochMilliseconds(),
        ) = ConversationEntity(
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
            model = conversation.model,
            contextWindowLimit = conversation.hostContextWindowLimit,
            modelEffort = conversation.modelSettings?.effectiveEffort,
            lastMessageAtEpochMs = ConversationRecency.epochMillisOrNull(conversation.lastMessageAt),
            updatedAtEpochMs = ConversationRecency.epochMillisOrNull(conversation.updatedAt),
            createdAtEpochMs = ConversationRecency.epochMillisOrNull(conversation.createdAt),
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
