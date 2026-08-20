package com.letta.mobile.data.local

import androidx.room.withTransaction
import com.letta.mobile.runtime.EpochMillis
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventEnvelope
import com.letta.mobile.runtime.RuntimeEventId
import com.letta.mobile.runtime.RuntimeEventOffset
import com.letta.mobile.runtime.RuntimeEventOutbox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json

class RoomRuntimeEventOutbox(
    private val database: LettaDatabase,
    private val eventIdFactory: (RuntimeEventDraft, RuntimeEventOffset) -> RuntimeEventId = { _, offset ->
        RuntimeEventId("runtime-event-${offset.value}")
    },
    private val clock: () -> EpochMillis = { EpochMillis(System.currentTimeMillis()) },
) : RuntimeEventOutbox {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun append(draft: RuntimeEventDraft): RuntimeEventEnvelope =
        appendAll(listOf(draft)).single()

    override suspend fun appendAll(drafts: List<RuntimeEventDraft>): List<RuntimeEventEnvelope> {
        if (drafts.isEmpty()) return emptyList()

        return database.withTransaction {
            val firstOffset = database.runtimeEventDao().maxOffset() + 1L
            val envelopes = drafts.mapIndexed { index, draft ->
                val offset = RuntimeEventOffset(firstOffset + index)
                RuntimeEventEnvelope(
                    offset = offset,
                    eventId = eventIdFactory(draft, offset),
                    backendId = draft.backendId,
                    runtimeId = draft.runtimeId,
                    agentId = draft.agentId,
                    conversationId = draft.conversationId,
                    runId = draft.runId,
                    createdAt = clock(),
                    source = draft.source,
                    payload = draft.payload,
                )
            }
            database.runtimeEventDao().insert(envelopes.map { RuntimeEventEntity.fromEnvelope(it, json) })
            envelopes
        }
    }

    override fun events(afterOffset: RuntimeEventOffset): Flow<RuntimeEventEnvelope> = flow {
        var cursor = afterOffset
        database.runtimeEventDao().observeMaxOffset().collect { maxOffset ->
            if (maxOffset == null || maxOffset <= cursor.value) {
                return@collect
            }

            database.runtimeEventDao()
                .listAfterOffset(cursor.value)
                .forEach { row ->
                    val event = row.toEnvelope(json)
                    if (event.offset > cursor) {
                        emit(event)
                        cursor = event.offset
                    }
                }
        }
    }
}
