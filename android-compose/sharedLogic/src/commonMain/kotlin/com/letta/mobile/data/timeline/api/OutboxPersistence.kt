package com.letta.mobile.data.timeline.api

import com.letta.mobile.data.timeline.CanonicalPendingLocalStore
import com.letta.mobile.data.timeline.TimelineStoreReader
import com.letta.mobile.data.timeline.TimelineStoreTransaction
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.timeline.parseTimelineInstantOrNull
import com.letta.mobile.data.timeline.timelineInstantDurationMillis
import kotlinx.serialization.Serializable

/** Live entries are capped by [OutboxTransitions.BUDGET]; this caps their encoded size. */
internal const val OUTBOX_MAX_BYTES: Int = 2 * 1024 * 1024

/**
 * Admitting a prompt stops short of [OUTBOX_MAX_BYTES]: a later transition grows its entry (a
 * failure reason, a longer attempt), and that write must never be refused, or the entry is stuck.
 */
internal const val OUTBOX_ADMISSION_MAX_BYTES: Int = OUTBOX_MAX_BYTES - OutboxTransitions.BUDGET * 64

private const val OUTBOX_KEY = "outbox/v2"
private const val LEGACY_OUTBOX_KEY = "pending/local/v1"
private const val OUTBOX_VERSION = 2

@Serializable
private data class OutboxEnvelope(val version: Int = OUTBOX_VERSION, val entries: List<OutboxEntry>)

@Serializable
private data class LegacyOutboxEnvelope(val version: Int = 1, val records: List<CanonicalPendingLocalStore.Record>)

/** The entries with [otid] replaced by [next], or removed when [next] is settled. */
internal fun List<OutboxEntry>.replacing(otid: String, next: OutboxEntry): List<OutboxEntry> =
    if (next.state.isTerminal) filterNot { it.otid == otid } else map { if (it.otid == otid) next else it }

/** The conversation's outbox; an older pending store's value reads as converted. */
internal suspend fun TimelineStoreReader.readOutbox(nowMillis: Long): List<OutboxEntry> {
    val bytes = evidence(OUTBOX_KEY, OUTBOX_MAX_BYTES) ?: return readLegacyOutbox(nowMillis)
    require(bytes.size <= OUTBOX_MAX_BYTES) { "Outbox byte budget exceeded" }
    val envelope = LettaJson.persisted.decodeFromString(OutboxEnvelope.serializer(), bytes.decodeToString())
    require(envelope.version == OUTBOX_VERSION) { "Unsupported outbox version" }
    return envelope.entries.also(::validateOutbox)
}

/**
 * Encodes [entries] (null when there are none), checking every budget, so a caller can refuse an
 * oversized prompt before anything leaves this device. Admission passes [OUTBOX_ADMISSION_MAX_BYTES].
 */
internal fun encodeOutbox(entries: List<OutboxEntry>, limit: Int = OUTBOX_MAX_BYTES): ByteArray? {
    if (entries.isEmpty()) return null
    validateOutbox(entries)
    // Reject oversized prompts before serialization allocates another copy of their text.
    val textUnits = entries.sumOf { entry ->
        entry.content.length.toLong() + entry.otid.length + entry.createdAt.length +
            entry.attachments.sumOf { it.base64.length.toLong() + it.mediaType.length }
    }
    require(textUnits <= limit) { "Outbox byte budget exceeded" }
    val bytes = LettaJson.persisted.encodeToString(OutboxEnvelope.serializer(), OutboxEnvelope(entries = entries))
        .encodeToByteArray()
    require(bytes.size <= limit) { "Outbox byte budget exceeded" }
    return bytes
}

/** Stores an [encodeOutbox] result. The legacy value goes in the same write, so it converts once. */
internal suspend fun TimelineStoreTransaction.putOutbox(encoded: ByteArray?) {
    if (encoded == null) deleteEvidence(OUTBOX_KEY) else putEvidence(OUTBOX_KEY, encoded)
    if (hasLegacyOutbox()) deleteEvidence(LEGACY_OUTBOX_KEY)
}

internal suspend fun TimelineStoreTransaction.writeOutbox(entries: List<OutboxEntry>, limit: Int = OUTBOX_MAX_BYTES) =
    putOutbox(encodeOutbox(entries, limit))

/**
 * A pending-store record as an outbox entry: Sending has been handed to the transport, Sent was
 * accepted and awaits its echo, and Failed is not known to have been received. The send entered
 * its state when it was sent, so that is when its clock starts.
 */
internal fun CanonicalPendingLocalStore.Record.toOutboxEntry(nowMillis: Long): OutboxEntry {
    val failed = delivery == CanonicalPendingLocalStore.Delivery.Failed
    return OutboxEntry(
        otid = otid,
        content = content,
        attachments = attachments,
        createdAt = sentAt,
        state = when (delivery) {
            CanonicalPendingLocalStore.Delivery.Sending -> OutboxState.Dispatching
            CanonicalPendingLocalStore.Delivery.Sent -> OutboxState.Accepted
            CanonicalPendingLocalStore.Delivery.Failed -> OutboxState.Failed
        },
        attempt = attempt,
        stateSinceMillis = sentAtMillis(sentAt, nowMillis),
        failure = if (failed) OutboxFailure.Unconfirmed else null,
    )
}

/** [sentAt] in epoch milliseconds, never later than [nowMillis]; now when it does not parse. */
private fun sentAtMillis(sentAt: String, nowMillis: Long): Long {
    val epoch = parseTimelineInstantOrNull(EPOCH) ?: return nowMillis
    val sent = parseTimelineInstantOrNull(sentAt) ?: return nowMillis
    return minOf(timelineInstantDurationMillis(epoch, sent), nowMillis)
}

private const val EPOCH = "1970-01-01T00:00:00Z"

internal suspend fun TimelineStoreReader.hasLegacyOutbox(): Boolean =
    evidence(LEGACY_OUTBOX_KEY, CanonicalPendingLocalStore.MAX_BYTES) != null

private suspend fun TimelineStoreReader.readLegacyOutbox(nowMillis: Long): List<OutboxEntry> {
    val bytes = evidence(LEGACY_OUTBOX_KEY, CanonicalPendingLocalStore.MAX_BYTES) ?: return emptyList()
    val legacy = LettaJson.persisted.decodeFromString(LegacyOutboxEnvelope.serializer(), bytes.decodeToString())
    require(legacy.version == 1) { "Unsupported pending state version" }
    return legacy.records.map { it.toOutboxEntry(nowMillis) }.also(::validateOutbox)
}

private fun validateOutbox(entries: List<OutboxEntry>) {
    require(entries.size <= OutboxTransitions.BUDGET) { "Outbox row budget exceeded" }
    require(entries.none { it.state.isTerminal }) { "A settled entry was stored" }
    require(entries.map { it.otid }.toSet().size == entries.size) { "Duplicate outbox otid" }
}

/**
 * Confirms the echo of [otid] inside the transaction that durably writes that echo, so the prompt
 * and its echo never both show. The enclosing transaction allocates the revision.
 */
internal suspend fun confirmOutboxEcho(transaction: TimelineStoreTransaction, otid: String, nowMillis: Long): Boolean {
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
