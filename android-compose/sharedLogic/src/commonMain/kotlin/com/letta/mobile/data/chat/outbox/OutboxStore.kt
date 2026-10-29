package com.letta.mobile.data.chat.outbox

import com.letta.mobile.data.timeline.CanonicalPendingLocalStore
import com.letta.mobile.data.timeline.TimelineBoundedStore
import com.letta.mobile.data.timeline.TimelineStoreReader
import com.letta.mobile.data.timeline.TimelineStoreTransaction
import com.letta.mobile.data.timeline.parseTimelineInstantOrNull
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.timelineCurrentTimeMillis
import com.letta.mobile.data.timeline.timelineInstantDurationMillis
import kotlinx.serialization.Serializable

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
            val previous = readOutbox(candidate.createdAtMillis)
            val admission = OutboxTransitions.admit(previous, candidate)
            if (admission == OutboxAdmission.Admitted) commit(previous + candidate)
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
        if (recovered != previous || hasLegacy()) commit(recovered)
        recovered
    }

    private suspend fun TimelineStoreTransaction.commit(entries: List<OutboxEntry>) {
        writeOutbox(entries)
        nextRevision()
    }

    companion object {
        /** Live entries are capped by [OutboxTransitions.BUDGET]; this caps their encoded size. */
        const val MAX_BYTES: Int = 2 * 1024 * 1024
        private const val KEY = "outbox/v2"
        private const val LEGACY_KEY = "pending/local/v1"
        private const val VERSION = 2

        /**
         * Confirms the echo of [otid] inside the transaction that durably writes that echo, so the
         * prompt and its echo never both show. The enclosing transaction allocates the revision.
         */
        suspend fun confirmEcho(transaction: TimelineStoreTransaction, otid: String, nowMillis: Long): Boolean {
            if (otid.isBlank()) return false
            return with(transaction) {
                val previous = readOutbox(nowMillis)
                val current = previous.firstOrNull { it.otid == otid } ?: return@with false
                val outcome = OutboxTransitions.next(current, OutboxEvent.EchoIngested, nowMillis)
                if (outcome !is OutboxTransition.Applied) return@with false
                writeOutbox(previous.replacing(otid, outcome.entry))
                true
            }
        }

        private fun List<OutboxEntry>.replacing(otid: String, next: OutboxEntry): List<OutboxEntry> =
            if (next.state.isTerminal) filterNot { it.otid == otid } else map { if (it.otid == otid) next else it }

        private suspend fun TimelineStoreReader.hasLegacy(): Boolean =
            evidence(LEGACY_KEY, CanonicalPendingLocalStore.MAX_BYTES) != null

        private suspend fun TimelineStoreReader.readOutbox(nowMillis: Long): List<OutboxEntry> {
            val bytes = evidence(KEY, MAX_BYTES) ?: return readLegacy(nowMillis)
            require(bytes.size <= MAX_BYTES) { "Outbox byte budget exceeded" }
            val envelope = TimelineSnapshotCodec.json.decodeFromString(Envelope.serializer(), bytes.decodeToString())
            require(envelope.version == VERSION) { "Unsupported outbox version" }
            return envelope.entries.also(::validate)
        }

        private suspend fun TimelineStoreReader.readLegacy(nowMillis: Long): List<OutboxEntry> {
            val bytes = evidence(LEGACY_KEY, CanonicalPendingLocalStore.MAX_BYTES) ?: return emptyList()
            val legacy = TimelineSnapshotCodec.json.decodeFromString(LegacyEnvelope.serializer(), bytes.decodeToString())
            require(legacy.version == 1) { "Unsupported pending state version" }
            return legacy.records.map { it.toOutboxEntry(nowMillis) }.also(::validate)
        }

        private fun validate(entries: List<OutboxEntry>) {
            require(entries.size <= OutboxTransitions.BUDGET) { "Outbox row budget exceeded" }
            require(entries.none { it.state.isTerminal }) { "A settled entry was stored" }
            require(entries.map { it.otid }.toSet().size == entries.size) { "Duplicate outbox otid" }
        }

        // The legacy value is removed in the same write, so it is converted at most once.
        private suspend fun TimelineStoreTransaction.writeOutbox(entries: List<OutboxEntry>) {
            if (entries.isEmpty()) deleteEvidence(KEY) else putEvidence(KEY, encode(entries))
            if (hasLegacy()) deleteEvidence(LEGACY_KEY)
        }

        private fun encode(entries: List<OutboxEntry>): ByteArray {
            validate(entries)
            // Reject oversized prompts before serialization allocates another copy of their text.
            val textUnits = entries.sumOf { entry ->
                entry.content.length.toLong() + entry.otid.length +
                    entry.attachments.sumOf { it.base64.length.toLong() + it.mediaType.length }
            }
            require(textUnits <= MAX_BYTES) { "Outbox byte budget exceeded" }
            val bytes = TimelineSnapshotCodec.json.encodeToString(Envelope.serializer(), Envelope(entries = entries))
                .encodeToByteArray()
            require(bytes.size <= MAX_BYTES) { "Outbox byte budget exceeded" }
            return bytes
        }

        /**
         * A legacy record has no history of where its input got to, so everything not already
         * failed is probed: Sending may or may not have left, and Sent is still awaiting its echo.
         */
        private fun CanonicalPendingLocalStore.Record.toOutboxEntry(nowMillis: Long): OutboxEntry {
            val failed = delivery == CanonicalPendingLocalStore.Delivery.Failed
            return OutboxEntry(
                otid = otid,
                content = content,
                attachments = attachments,
                createdAtMillis = epochMillisOrNull(sentAt) ?: nowMillis,
                state = if (failed) OutboxState.Failed else OutboxState.Unknown,
                attempt = attempt,
                stateSinceMillis = nowMillis,
                failure = if (failed) OutboxFailure.Unconfirmed else null,
            )
        }

        private fun epochMillisOrNull(timestamp: String): Long? {
            val epoch = parseTimelineInstantOrNull(EPOCH) ?: return null
            val instant = parseTimelineInstantOrNull(timestamp) ?: return null
            return timelineInstantDurationMillis(epoch, instant)
        }

        private const val EPOCH = "1970-01-01T00:00:00Z"
    }

    @Serializable
    private data class Envelope(val version: Int = VERSION, val entries: List<OutboxEntry>)

    @Serializable
    private data class LegacyEnvelope(val version: Int = 1, val records: List<CanonicalPendingLocalStore.Record>)
}
