package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.api.OutboxAdmission
import com.letta.mobile.data.timeline.api.OutboxEntry
import com.letta.mobile.data.timeline.api.OutboxEvent
import com.letta.mobile.data.timeline.api.OutboxState
import com.letta.mobile.data.timeline.api.OUTBOX_ADMISSION_MAX_BYTES
import com.letta.mobile.data.timeline.api.OUTBOX_MAX_BYTES
import com.letta.mobile.data.timeline.api.confirmOutboxEcho
import com.letta.mobile.data.timeline.api.OutboxTransition
import com.letta.mobile.data.timeline.api.OutboxTransitions
import com.letta.mobile.data.timeline.api.readOutbox
import com.letta.mobile.data.timeline.api.replacing
import com.letta.mobile.data.timeline.api.toOutboxEntry
import com.letta.mobile.data.timeline.api.writeOutbox
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Bounded durable optimistic state, independent of screen residency and settled history.
 *
 * The records are a view of the conversation's outbox: every change below is an outbox
 * transition, compared and set by attempt inside one store transaction, and the lost-echo horizon
 * counts from when the current attempt began, so a retried old prompt gets a fresh one.
 */
class CanonicalPendingLocalStore(
    private val store: TimelineBoundedStore,
    private val recoveryProtected: (String) -> Boolean = { false },
) {
    @Serializable
    data class Record(
        val otid: String,
        val content: String,
        val attachments: List<@Serializable(with = PendingImageSerializer::class) MessageContentPart.Image>,
        val sentAt: String,
        val delivery: Delivery = Delivery.Sending,
        /** Retry generation persisted with this message to fence delayed callbacks. */
        val attempt: Long = 0,
    )

    // Local persistence shape only; outbound images retain their existing wire encoding.
    internal object PendingImageSerializer : KSerializer<MessageContentPart.Image> {
        @Serializable
        private data class StoredImage(val base64: String, val mediaType: String)

        override val descriptor = StoredImage.serializer().descriptor

        override fun serialize(encoder: Encoder, value: MessageContentPart.Image) =
            encoder.encodeSerializableValue(StoredImage.serializer(), StoredImage(value.base64, value.mediaType))

        override fun deserialize(decoder: Decoder): MessageContentPart.Image {
            val stored = decoder.decodeSerializableValue(StoredImage.serializer())
            return MessageContentPart.Image(stored.base64, stored.mediaType)
        }
    }

    @Serializable
    enum class Delivery { Sending, Sent, Failed }

    suspend fun load(scope: TimelineScope): List<Record> =
        store.read(scope) { readOutbox(timelineCurrentTimeMillis()).map { it.toRecord() } }

    /** Persistence failure propagates to the caller before a transport send may be accepted. */
    suspend fun save(scope: TimelineScope, record: Record) {
        require(record.otid.isNotBlank())
        store.transaction(scope) {
            val now = timelineCurrentTimeMillis()
            val previous = readOutbox(now)
            val candidate = record.toOutboxEntry(now).stampedHere()
            // A later send is not permission to discard an earlier, unconfirmed prompt.
            if (admitted(previous, candidate)) commit(previous + candidate, OUTBOX_ADMISSION_MAX_BYTES)
        }
    }

    /**
     * Reserves the prompt, hands it to [accept] outside any store transaction, and releases the
     * reservation when the transport refuses or throws, so a slow transport never holds the store.
     * A process death after the reservation leaves a Dispatching prompt, which recovery resolves.
     */
    internal suspend fun accept(scope: TimelineScope, record: Record, accept: () -> Boolean): Boolean {
        require(record.otid.isNotBlank())
        val reservation = reserve(scope, record)
        var accepted = false
        try {
            accepted = accept()
        } finally {
            // Runs for a refusal and for a throw alike; a throw still propagates unchanged.
            if (!accepted) releaseQuietly(scope, reservation)
        }
        return accepted
    }

    /**
     * Cleanup runs even if the caller is cancelled, and its own failure never replaces the
     * transport's outcome; a reservation it cannot undo is resolved like any lost send.
     */
    private suspend fun releaseQuietly(scope: TimelineScope, reservation: Pair<OutboxEntry?, OutboxEntry>?) {
        withContext(NonCancellable) { runCatching { release(scope, reservation) } }
    }

    /**
     * Admits a new prompt as Dispatching, or dispatches a queued retry, within the budgets checked
     * before the transport is called. Returns the entry before and after; null when an existing
     * prompt is not queued, which a repeat acceptance leaves unchanged.
     */
    private suspend fun reserve(scope: TimelineScope, record: Record): Pair<OutboxEntry?, OutboxEntry>? =
        store.transaction(scope) {
            val now = timelineCurrentTimeMillis()
            val previous = readOutbox(now)
            val candidate = record.toOutboxEntry(now).stampedHere()
            if (admitted(previous, candidate)) {
                commit(previous + candidate, OUTBOX_ADMISSION_MAX_BYTES)
                return@transaction null to candidate
            }
            val existing = previous.first { it.otid == candidate.otid }
            val dispatched = existing.after(listOf(OutboxEvent.DispatchStart), now)?.sinceAttemptStart(existing)
                ?.stampedHere() ?: return@transaction null
            commit(previous.replacing(existing.otid, dispatched))
            existing to dispatched
        }

    /** Undoes [reservation] only while the entry is still exactly what it wrote. */
    private suspend fun release(scope: TimelineScope, reservation: Pair<OutboxEntry?, OutboxEntry>?) {
        val (before, written) = reservation ?: return
        store.transaction(scope) {
            val previous = readOutbox(timelineCurrentTimeMillis())
            if (previous.none { it == written }) return@transaction
            val restored = when (before) {
                null -> previous.filterNot { it.otid == written.otid }
                else -> previous.replacing(written.otid, before)
            }
            commit(restored)
        }
    }

    /**
     * Mark overdue unconfirmed sends as failed, retaining every prompt for explicit Retry or
     * dismissal. A transport drop can leave either Sending or Sent without an echo. A send this
     * process is still delivering while its conversation is [streaming] is not overdue.
     */
    suspend fun retireLostEchoes(
        scope: TimelineScope,
        loaded: List<Record>,
        now: TimelineInstant = timelineNow(),
        streaming: Boolean = false,
    ): Int {
        // Callers already hold a freshly loaded list, and a send enters its state no earlier than it
        // was sent, so a younger send cannot be stale: refresh runs on ordinary traffic and must not
        // bill a commit and a revision when nothing is.
        if (loaded.none { mayBeStale(it, now) }) return 0
        val nowMillis = now.epochMillis()
        return store.transaction(scope) {
            val previous = readOutbox(nowMillis)
            val next = previous.map { entry ->
                entry.takeIf { it.state != OutboxState.Failed && !recoveryProtected(it.otid) }
                    ?.takeIf { !it.inFlightHere(nowMillis, LOST_ECHO_HORIZON_MILLIS, streaming) }
                    ?.takeIf { nowMillis - it.stateSinceMillis >= LOST_ECHO_HORIZON_MILLIS }
                    ?.after(LOST_EVENTS[entry.state].orEmpty(), nowMillis)
                    ?: entry
            }
            val retired = next.indices.count { next[it] !== previous[it] }
            if (retired > 0) commit(next)
            retired
        }
    }

    /**
     * The otids this process may still be delivering (see [OutboxEntry.inFlightHere]). Every other
     * unconfirmed send belongs to no live attempt, so history decides it.
     */
    internal suspend fun inFlightHere(scope: TimelineScope, streaming: Boolean): Set<String> {
        val now = timelineCurrentTimeMillis()
        return store.read(scope) {
            readOutbox(now).filter { it.inFlightHere(now, LOST_ECHO_HORIZON_MILLIS, streaming) }
                .mapTo(mutableSetOf()) { it.otid }
        }
    }

    /**
     * Whether [record] could be past the horizon: unconfirmed and sent long enough ago. An
     * unparsable timestamp is not evidence of staleness, so such a record is left alone.
     */
    private fun mayBeStale(record: Record, now: TimelineInstant): Boolean {
        if (record.delivery == Delivery.Failed) return false
        val sent = runCatching { parseTimelineInstant(record.sentAt) }.getOrNull() ?: return false
        return timelineInstantDurationMillis(sent, now) >= LOST_ECHO_HORIZON_MILLIS
    }

    /**
     * Apply a transport outcome to the expected retry generation while retaining unconfirmed
     * messages. Only an explicit Retry moves a failed record back to Sending, so a late Sent
     * cannot undo a failure.
     */
    suspend fun mark(scope: TimelineScope, otid: String, delivery: Delivery, expectedAttempt: Long? = null) {
        store.transaction(scope) {
            val now = timelineCurrentTimeMillis()
            val previous = readOutbox(now)
            val entry = previous.firstOrNull { it.otid == otid } ?: return@transaction
            if (expectedAttempt != null && entry.attempt != expectedAttempt) return@transaction
            val next = entry.after(outcomeEvents(delivery)[entry.state].orEmpty(), now)?.sinceAttemptStart(entry)
                ?.stampedHere() ?: return@transaction
            commit(previous.replacing(otid, next))
        }
    }

    // Resends at once, as before; reading history first arrives with the outbox worker.
    internal suspend fun retry(scope: TimelineScope, otid: String): Record? = store.transaction(scope) {
        val now = timelineCurrentTimeMillis()
        val previous = readOutbox(now)
        val failed = previous.singleOrNull { it.otid == otid && it.state == OutboxState.Failed } ?: return@transaction null
        check(failed.attempt < Long.MAX_VALUE)
        val retry = failed.after(listOf(OutboxEvent.UserResendAnyway), now)?.stampedHere() ?: return@transaction null
        commit(previous.replacing(otid, retry))
        retry.toRecord()
    }

    /**
     * Drop a send the user has given up on. An absent echo is never confirmation, so nothing else
     * removes a pending record; without this a permanently failed send is durable forever and the
     * bubble it draws can never be dismissed. Only a failed record qualifies: one still in flight,
     * or already accepted by the transport, may still be echoed.
     */
    suspend fun discardFailed(scope: TimelineScope, otid: String): Boolean {
        require(otid.isNotBlank())
        return store.transaction(scope) {
            val now = timelineCurrentTimeMillis()
            val previous = readOutbox(now)
            val failed = previous.firstOrNull { it.otid == otid && it.state == OutboxState.Failed } ?: return@transaction false
            val dismissed = failed.after(listOf(OutboxEvent.UserDismiss), now) ?: return@transaction false
            commit(previous.replacing(otid, dismissed))
            true
        }
    }

    /** Called inside the same transaction that durably confirms the server echo. */
    internal suspend fun confirm(transaction: TimelineStoreTransaction, otid: String) {
        confirmEcho(transaction, otid)
    }

    private suspend fun TimelineStoreTransaction.commit(entries: List<OutboxEntry>, limit: Int = OUTBOX_MAX_BYTES) {
        writeOutbox(entries, limit)
        nextRevision()
    }

    companion object {
        // The enclosing echo transaction owns revision allocation, including replay-only removal.
        internal suspend fun confirmEcho(transaction: TimelineStoreTransaction, otid: String): Boolean =
            confirmOutboxEcho(transaction, otid, timelineCurrentTimeMillis())

        /** How long a send counts as in flight without an echo, unless its conversation is streaming. */
        const val LOST_ECHO_HORIZON_MILLIS = 5L * 60L * 1000L

        const val MAX_RECORDS = OutboxTransitions.BUDGET
        const val MAX_BYTES = OUTBOX_MAX_BYTES
    }
}

/** Refuses a conflicting or over-budget prompt; false when the same prompt is already pending. */
private fun admitted(previous: List<OutboxEntry>, candidate: OutboxEntry): Boolean {
    val admission = OutboxTransitions.admit(previous, candidate, CanonicalPendingLocalStore.MAX_RECORDS)
    require(admission != OutboxAdmission.IdentityConflict) { "Pending identity already belongs to another message" }
    require(admission != OutboxAdmission.OverBudget) { "Pending row budget exceeded" }
    return admission == OutboxAdmission.Admitted
}

/** The entry after [events] in order, or null when there are none or the table refuses one. */
private fun OutboxEntry.after(events: List<OutboxEvent>, now: Long): OutboxEntry? {
    if (events.isEmpty()) return null
    return events.fold<OutboxEvent, OutboxEntry?>(this) { entry, event ->
        entry?.let { (OutboxTransitions.next(it, event, now, it.attempt) as? OutboxTransition.Applied)?.entry }
    }
}

/**
 * A transport outcome is about the current attempt, so the lost-echo horizon keeps counting from
 * when that attempt began: the first send, or the retry that started it.
 */
private fun OutboxEntry.sinceAttemptStart(before: OutboxEntry): OutboxEntry =
    if (state == OutboxState.Failed) this else copy(stateSinceMillis = before.stateSinceMillis)

private fun OutboxEntry.toRecord(): CanonicalPendingLocalStore.Record = CanonicalPendingLocalStore.Record(
    otid = otid,
    content = content,
    attachments = attachments,
    sentAt = createdAt,
    delivery = when (state) {
        OutboxState.Queued, OutboxState.Dispatching -> CanonicalPendingLocalStore.Delivery.Sending
        OutboxState.Accepted, OutboxState.Unknown -> CanonicalPendingLocalStore.Delivery.Sent
        else -> CanonicalPendingLocalStore.Delivery.Failed
    },
    attempt = attempt,
)

private fun TimelineInstant.epochMillis(): Long = timelineInstantDurationMillis(parseTimelineInstant(EPOCH), this)

private const val EPOCH = "1970-01-01T00:00:00Z"

private fun outcomeEvents(delivery: CanonicalPendingLocalStore.Delivery): Map<OutboxState, List<OutboxEvent>> =
    when (delivery) {
        CanonicalPendingLocalStore.Delivery.Sending -> emptyMap()
        CanonicalPendingLocalStore.Delivery.Sent -> SENT_EVENTS
        CanonicalPendingLocalStore.Delivery.Failed -> FAILED_EVENTS
    }

// The transport accepted the input; a queued retry is dispatched first.
private val SENT_EVENTS = mapOf(
    OutboxState.Queued to listOf(OutboxEvent.DispatchStart, OutboxEvent.TransportAccepted),
    OutboxState.Dispatching to listOf(OutboxEvent.TransportAccepted),
)

// Before acceptance the input never reached the server. After it, a caller reports failure only
// once it has found no echo, which is what a history probe concludes.
private val FAILED_EVENTS = mapOf(
    OutboxState.Queued to listOf(OutboxEvent.DispatchStart, OutboxEvent.TransportRejected),
    OutboxState.Dispatching to listOf(OutboxEvent.TransportRejected),
    OutboxState.Accepted to listOf(OutboxEvent.Disconnected, OutboxEvent.ProbeNoEcho),
    OutboxState.Unknown to listOf(OutboxEvent.ProbeNoEcho),
)

// No owner and no echo for a whole horizon: an undispatched retry never left; anything else is lost.
private val LOST_EVENTS = mapOf(
    OutboxState.Queued to listOf(OutboxEvent.DispatchStart, OutboxEvent.TransportRejected),
    OutboxState.Dispatching to listOf(OutboxEvent.Disconnected, OutboxEvent.ProbeNoEcho),
    OutboxState.Accepted to listOf(OutboxEvent.EchoTimeout, OutboxEvent.ProbeNoEcho),
    OutboxState.Unknown to listOf(OutboxEvent.ProbeNoEcho),
)
