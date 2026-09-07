package com.letta.mobile.data.timeline

import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import com.letta.mobile.data.timeline.snapshot.toConfirmedTimelineEvent
import com.letta.mobile.data.timeline.snapshot.toStoredTimelineEvent

/** Retry the same page/frame after resolving this condition; the transaction has not committed. */
class TimelineMergeUnavailable(val identity: TimelineMessageId, val reason: String) :
    IllegalStateException("Canonical merge unavailable for ${identity.value}: $reason")

/** Exact terminal ownership and stable indexed positions, with no history-wide materialization. */
class TimelineExactCanonicalWriter(
    private val scope: TimelineScope,
    private val maxHistoricalBytes: Int,
) : TimelineCanonicalWriter {
    init { require(maxHistoricalBytes > 0) }

    override suspend fun merge(transaction: TimelineStoreTransaction, record: TimelineRemoteRecord): Boolean {
        val incoming = record.message.toTimelineEvent(0.0)
        if (incoming != null) return mergeEvent(transaction, incoming)
        // Opaque protocol records must survive even when the current renderer cannot project them.
        val key = transaction.locate(record.identity) ?: TimelinePageKey(
            record.message.date?.let(::parseTimelineInstantOrNull)?.let {
                timelineInstantDurationMillis(parseTimelineInstant("1970-01-01T00:00:00Z"), it)
            } ?: 0, record.identity,
        )
        val bytes = TimelineSnapshotCodec.json.encodeToString(com.letta.mobile.data.model.LettaMessage.serializer(), record.message).encodeToByteArray()
        transaction.put(TimelineStoredRecord(key, "application/vnd.letta.message+json;version=1", bytes))
        return true
    }

    suspend fun mergeEvent(transaction: TimelineStoreTransaction, incoming: TimelineEvent.Confirmed): Boolean {
        val identity = canonicalIdentity(transaction, incoming.serverId, incoming.otid)
        val ownerKey = "terminal/server/${identity.value}"
        val ownerBytes = transaction.evidence(ownerKey, 64 * 1024)
        val owner = ownerBytes?.let {
            TimelineSnapshotCodec.json.decodeFromString(TerminalOwnershipEvidence.serializer(), it.decodeToString())
        }
        val key = transaction.locate(identity) ?: TimelinePageKey(
            timelineInstantDurationMillis(parseTimelineInstant("1970-01-01T00:00:00Z"), incoming.date), identity,
        )
        val old = transaction.metadata(TimelineReadPosition.Around(key), 1).rows.singleOrNull { it.key == key }
        var historical: TimelineEvent.Confirmed? = null
        if (old != null) {
            if (old.body.encodedBytes > maxHistoricalBytes) throw TimelineMergeUnavailable(identity, "historical_body_budget")
            val bytes = ByteArray(old.body.encodedBytes.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val chunk = transaction.body(old.body, offset.toLong(), minOf(64 * 1024, bytes.size - offset))
                if (chunk.isEmpty() || chunk.size > bytes.size - offset) throw TimelineMergeUnavailable(identity, "incomplete_historical_body")
                chunk.copyInto(bytes, offset)
                offset += chunk.size
            }
            historical = TimelineSnapshotCodec.json.decodeFromString(StoredTimelineEvent.serializer(), bytes.decodeToString()).toConfirmedTimelineEvent()
        }
        val merged = if (owner != null) {
            when (val decision = mergeOwnedTerminal(scope, owner, incoming, maxHistoricalBytes.toLong(),
                TerminalHistoricalBodyReader { _, _, _ -> historical })) {
                is TerminalEvidenceDecision.Changed -> decision.event
                TerminalEvidenceDecision.Unchanged -> return false
                is TerminalEvidenceDecision.Unavailable -> throw TimelineMergeUnavailable(identity, decision.reason)
            }
        } else historical?.let { TimelineHydrationReducer.mergeRicherEventFacts(incoming, it).copy(position = it.position, otid = it.otid) } ?: incoming
        if (merged == historical) return false
        val canonical = merged.copy(serverId = identity.value)
        val bytes = TimelineSnapshotCodec.json.encodeToString(StoredTimelineEvent.serializer(), canonical.toStoredTimelineEvent()).encodeToByteArray()
        if (canonical.otid.isNotBlank()) transaction.putEvidence("identity/otid/${canonical.otid}", identity.value.encodeToByteArray())
        transaction.put(TimelineStoredRecord(key, "application/vnd.letta.timeline-event+json;version=1", bytes))
        if (merged.messageType == TimelineMessageType.ASSISTANT) {
            val evidence = TerminalOwnershipEvidence.checkpoint(scope, canonical)
            transaction.putEvidence(ownerKey, TimelineSnapshotCodec.json.encodeToString(TerminalOwnershipEvidence.serializer(), evidence).encodeToByteArray())
        }
        return true
    }

    internal suspend fun canonicalIdentity(reader: TimelineStoreReader, serverId: String, otid: String): TimelineMessageId {
        val alias = otid.takeIf { it.isNotBlank() }?.let {
            reader.evidence("identity/otid/$it", 64 * 1024)?.decodeToString(throwOnInvalidSequence = true)
        }
        return TimelineMessageId(alias ?: serverId)
    }
}
