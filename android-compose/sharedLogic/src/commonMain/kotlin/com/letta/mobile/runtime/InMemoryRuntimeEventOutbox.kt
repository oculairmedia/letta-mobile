package com.letta.mobile.runtime

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryRuntimeEventOutbox(
    private val eventIdFactory: (RuntimeEventDraft, RuntimeEventOffset) -> RuntimeEventId,
    private val clock: () -> EpochMillis,
) : RuntimeEventOutbox {
    private val mutex = Mutex()
    private val events = MutableStateFlow<List<RuntimeEventEnvelope>>(emptyList())

    override suspend fun append(draft: RuntimeEventDraft): RuntimeEventEnvelope =
        mutex.withLock {
            val offset = RuntimeEventOffset((events.value.lastOrNull()?.offset?.value ?: 0L) + 1L)
            val envelope = RuntimeEventEnvelope(
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
            events.value = events.value + envelope
            envelope
        }

    override fun events(afterOffset: RuntimeEventOffset): Flow<RuntimeEventEnvelope> = flow {
        var cursor = afterOffset
        events.collect { snapshot ->
            snapshot
                .asSequence()
                .filter { it.offset > cursor }
                .sortedBy { it.offset.value }
                .forEach { event ->
                    emit(event)
                    cursor = event.offset
                }
        }
    }
}
