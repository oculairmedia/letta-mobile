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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Room-backed [RuntimeEventOutbox].
 *
 * Retention (letta-mobile-by4vm.18): no consumer persists a committed offset for this
 * outbox (subscribers pass their own `afterOffset` to [events]), so there is no "acked"
 * watermark to prune against. Rows are instead bounded by age ([maxAgeMillis]) and count
 * ([maxRows]); pruning runs inside every [appendAll] transaction and once on first use
 * of the outbox (the constructor is not suspending, so the construction-time prune runs
 * on the first [appendAll] or [events] call instead of doing DB I/O in the constructor).
 *
 * Offsets are allocated as `MAX(eventOffset) + 1`, so the prune queries never delete the
 * newest row: the max can never regress, and offsets (and the default
 * `runtime-event-$offset` ids) stay unique across prunes without SQLite AUTOINCREMENT.
 */
class RoomRuntimeEventOutbox(
    private val database: LettaDatabase,
    private val eventIdFactory: (RuntimeEventDraft, RuntimeEventOffset) -> RuntimeEventId = { _, offset ->
        RuntimeEventId("runtime-event-${offset.value}")
    },
    private val clock: () -> EpochMillis = { EpochMillis(System.currentTimeMillis()) },
    private val maxRows: Int = DEFAULT_MAX_ROWS,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) : RuntimeEventOutbox {
    init {
        require(maxRows >= 1) { "maxRows must keep at least the newest row." }
        require(pageSize >= 1) { "pageSize must be positive." }
    }

    private val initialPruneDone = AtomicBoolean(false)
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
            pruneLocked()
            initialPruneDone.set(true)
            envelopes
        }
    }

    /** Applies the age and row-count retention. Never deletes the newest row. */
    suspend fun prune() {
        database.withTransaction { pruneLocked() }
    }

    private suspend fun pruneLocked() {
        val dao = database.runtimeEventDao()
        dao.deleteOlderThan(clock().value - maxAgeMillis)
        dao.deleteBeyondNewest(maxRows)
    }

    private suspend fun pruneOnFirstUse() {
        if (initialPruneDone.compareAndSet(false, true)) prune()
    }

    override fun events(afterOffset: RuntimeEventOffset): Flow<RuntimeEventEnvelope> = flow {
        pruneOnFirstUse()
        var cursor = afterOffset
        database.runtimeEventDao().observeMaxOffset().collect { maxOffset ->
            // Page from the cursor up to the observed max; never load the whole tail at once.
            while (maxOffset != null && maxOffset > cursor.value) {
                val page = database.runtimeEventDao().listAfterOffset(cursor.value, pageSize)
                if (page.isEmpty()) break
                page.forEach { row ->
                    val event = row.toEnvelope(json)
                    emit(event)
                    cursor = event.offset
                }
            }
        }
    }

    companion object {
        /** Hard cap on persisted runtime events; the newest rows win. */
        const val DEFAULT_MAX_ROWS: Int = 5_000

        /** Events older than this are pruned (7 days). */
        const val DEFAULT_MAX_AGE_MILLIS: Long = 7L * 24 * 60 * 60 * 1_000

        /** Rows read per [RuntimeEventDao.listAfterOffset] call while replaying [events]. */
        const val DEFAULT_PAGE_SIZE: Int = 200
    }
}
