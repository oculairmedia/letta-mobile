package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.api.OUTBOX_ADMISSION_MAX_BYTES
import com.letta.mobile.data.timeline.api.OUTBOX_MAX_BYTES
import com.letta.mobile.data.timeline.api.OutboxAdmission
import com.letta.mobile.data.timeline.api.OutboxEntry
import com.letta.mobile.data.timeline.api.OutboxEvent
import com.letta.mobile.data.timeline.api.OutboxTransition
import com.letta.mobile.data.timeline.api.OutboxTransitions
import com.letta.mobile.data.timeline.api.OutboxState
import com.letta.mobile.data.timeline.api.readOutbox
import com.letta.mobile.data.timeline.api.replacing
import com.letta.mobile.data.timeline.api.writeOutbox
import com.letta.mobile.data.timeline.api.hasLegacyOutbox
import com.letta.mobile.data.timeline.snapshot.TimelineScope

/**
 * The durable outbox of one conversation: one evidence value per [TimelineScope], written in the
 * same store transaction as everything else the timeline commits. Every change is a
 * compare-and-set: [transition] applies an event only to the entry's current attempt and state,
 * and a Confirmed or Dismissed entry is removed.
 *
 * An outbox written by the older pending store is read as if converted and rewritten in this
 * format on the first write, in that write's transaction.
 */
internal class OutboxStore(
    private val store: TimelineBoundedStore,
    private val clock: () -> Long = ::timelineCurrentTimeMillis,
) {
    suspend fun load(scope: TimelineScope): List<OutboxEntry> = store.read(scope) { readOutbox(clock()) }

    /** Admits [candidate] against the budget and persists it before any transport call. */
    suspend fun enqueue(scope: TimelineScope, candidate: OutboxEntry): OutboxAdmission {
        require(candidate.state == OutboxState.Queued) { "Only a queued entry can be admitted" }
        return store.transaction(scope) {
            val previous = readOutbox(clock())
            val admission = OutboxTransitions.admit(previous, candidate)
            if (admission == OutboxAdmission.Admitted) commit(previous + candidate, OUTBOX_ADMISSION_MAX_BYTES)
            admission
        }
    }

    /**
     * Offers [event] to the entry for [otid] and persists the result. Returns null when there is
     * no such entry, which is how an event for an already confirmed or dismissed prompt ends.
     */
    suspend fun transition(
        scope: TimelineScope,
        otid: String,
        event: OutboxEvent,
        attempt: Long? = null,
    ): OutboxTransition? = store.transaction(scope) {
        val now = clock()
        val previous = readOutbox(now)
        val current = previous.firstOrNull { it.otid == otid } ?: return@transaction null
        val outcome = OutboxTransitions.next(current, event, now, attempt)
        if (outcome is OutboxTransition.Applied) commit(previous.replacing(otid, outcome.entry))
        outcome
    }

    /** Called once when the conversation opens: nothing survives process death in flight. */
    suspend fun recoverAfterRestart(scope: TimelineScope): List<OutboxEntry> = store.transaction(scope) {
        val now = clock()
        val previous = readOutbox(now)
        val recovered = previous.map { it.afterRestart(now) }
        if (recovered != previous || hasLegacyOutbox()) commit(recovered)
        recovered
    }

    private suspend fun TimelineStoreTransaction.commit(entries: List<OutboxEntry>, limit: Int = OUTBOX_MAX_BYTES) {
        writeOutbox(entries, limit)
        nextRevision()
    }
}
