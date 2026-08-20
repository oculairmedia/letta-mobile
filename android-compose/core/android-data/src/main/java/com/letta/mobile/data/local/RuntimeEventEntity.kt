package com.letta.mobile.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.EpochMillis
import com.letta.mobile.runtime.RunId
import com.letta.mobile.runtime.RuntimeEventEnvelope
import com.letta.mobile.runtime.RuntimeEventId
import com.letta.mobile.runtime.RuntimeEventOffset
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeEventSource
import com.letta.mobile.runtime.RuntimeId
import kotlinx.serialization.json.Json

@Entity(
    tableName = "runtime_events",
    indices = [
        Index(value = ["eventId"], unique = true),
        Index(value = ["backendId", "runtimeId", "eventOffset"]),
        Index(value = ["conversationId"]),
        Index(value = ["agentId"]),
        Index(value = ["runId"]),
    ],
)
data class RuntimeEventEntity(
    @PrimaryKey val eventOffset: Long,
    val eventId: String,
    val backendId: String,
    val runtimeId: String,
    val agentId: String? = null,
    val conversationId: String? = null,
    val runId: String? = null,
    val createdAtEpochMs: Long,
    val source: String,
    val schemaVersion: Int,
    val payloadJson: String,
) {
    fun toEnvelope(json: Json): RuntimeEventEnvelope = RuntimeEventEnvelope(
        offset = RuntimeEventOffset(eventOffset),
        eventId = RuntimeEventId(eventId),
        backendId = BackendId(backendId),
        runtimeId = RuntimeId(runtimeId),
        agentId = agentId?.let(::AgentId),
        conversationId = conversationId?.let(::ConversationId),
        runId = runId?.let(::RunId),
        createdAt = EpochMillis(createdAtEpochMs),
        source = RuntimeEventSource.valueOf(source),
        schemaVersion = schemaVersion,
        payload = json.decodeFromString(RuntimeEventPayload.serializer(), payloadJson),
    )

    companion object {
        fun fromEnvelope(envelope: RuntimeEventEnvelope, json: Json): RuntimeEventEntity =
            RuntimeEventEntity(
                eventOffset = envelope.offset.value,
                eventId = envelope.eventId.value,
                backendId = envelope.backendId.value,
                runtimeId = envelope.runtimeId.value,
                agentId = envelope.agentId?.value,
                conversationId = envelope.conversationId?.value,
                runId = envelope.runId?.value,
                createdAtEpochMs = envelope.createdAt.value,
                source = envelope.source.name,
                schemaVersion = envelope.schemaVersion,
                payloadJson = json.encodeToString(RuntimeEventPayload.serializer(), envelope.payload),
            )
    }
}
