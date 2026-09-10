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
        if (incoming != null) {
            // toTimelineEvent intentionally omits the return's call ID; capture the wire identity
            // before projection so returns on a different history page resolve old owners.
            val returned = record.message as? com.letta.mobile.data.model.ToolReturnMessage
            val callId = returned?.toolReturn?.toolCallId?.takeIf { it.isNotBlank() }
            val indexed = if (callId != null) CanonicalToolIndex.observe(transaction, callId, null, true) else false
            return mergeEvent(transaction, incoming) || indexed
        }
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
        val identity = canonicalEventIdentity(transaction, incoming)
        val suppression = transaction.evidence("suppression/server/${identity.value}", 64 * 1024)
        if (suppression != null && incoming.messageType == TimelineMessageType.ASSISTANT) {
            val decision = TimelineSnapshotCodec.json.decodeFromString(
                AbandonedAssistantFragmentSuppression.serializer(), suppression.decodeToString(),
            )
            if (decision == incoming.toAbandonedAssistantFragmentSuppression()) return false
        }
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
        var historicalBytes: ByteArray? = null
        if (old != null) {
            if (old.body.encodedBytes > maxHistoricalBytes) throw TimelineMergeUnavailable(identity, "historical_body_budget")
            val stored = ByteArray(old.body.encodedBytes.toInt())
            var offset = 0
            while (offset < stored.size) {
                val chunk = transaction.body(old.body, offset.toLong(), minOf(64 * 1024, stored.size - offset))
                if (chunk.isEmpty() || chunk.size > stored.size - offset) throw TimelineMergeUnavailable(identity, "incomplete_historical_body")
                chunk.copyInto(stored, offset)
                offset += chunk.size
            }
            historicalBytes = stored
            historical = TimelineSnapshotCodec.json.decodeFromString(StoredTimelineEvent.serializer(), stored.decodeToString()).toConfirmedTimelineEvent()
        }
        val merged = if (owner != null) {
            when (val decision = mergeOwnedTerminal(scope, owner, incoming, maxHistoricalBytes.toLong(),
                TerminalHistoricalBodyReader { _, _, _ -> historical })) {
                is TerminalEvidenceDecision.Changed -> decision.event
                TerminalEvidenceDecision.Unchanged -> historical ?: throw TimelineMergeUnavailable(identity, "missing_terminal_body_for_index")
                is TerminalEvidenceDecision.Unavailable -> throw TimelineMergeUnavailable(identity, decision.reason)
            }
        } else historical?.let { TimelineHydrationReducer.mergeRicherEventFacts(incoming, it).copy(position = it.position, otid = it.otid) } ?: incoming
        // The echo and optimistic removal share the caller's transaction, even on replay.
        // Assistant frames can carry an otid too; only a user echo confirms a local send.
        var indexed = false
        if (incoming.messageType == TimelineMessageType.USER) {
            indexed = CanonicalPendingLocalStore.confirmEcho(transaction, incoming.otid) || indexed
        }
        for (call in merged.toolCalls) {
            if (call.effectiveId.isBlank()) continue
            indexed = CanonicalToolIndex.observe(transaction, call.effectiveId, identity,
                call.effectiveId in merged.toolReturnContentByCallId) || indexed
        }
        for (callId in merged.toolReturnContentByCallId.keys) {
            if (callId.isNotBlank()) indexed = CanonicalToolIndex.observe(transaction, callId, null, true) || indexed
        }
        indexed = transaction.indexIdentityAliases(incoming, merged.otid, identity) || indexed
        val canonical = merged.copy(serverId = identity.value)
        val bytes = TimelineSnapshotCodec.json.encodeToString(StoredTimelineEvent.serializer(), canonical.toStoredTimelineEvent()).encodeToByteArray()
        if (historicalBytes != null && bytes.contentEquals(historicalBytes)) return indexed
        if (canonical.otid.isNotBlank()) transaction.putEvidence("identity/otid/${canonical.otid}", identity.value.encodeToByteArray())
        transaction.put(TimelineStoredRecord(key, "application/vnd.letta.timeline-event+json;version=1", bytes))
        if (merged.messageType == TimelineMessageType.ASSISTANT) {
            val evidence = TerminalOwnershipEvidence.checkpoint(scope, canonical)
            transaction.putEvidence(ownerKey, TimelineSnapshotCodec.json.encodeToString(TerminalOwnershipEvidence.serializer(), evidence).encodeToByteArray())
        }
        return true
    }

    /**
     * Records how else this message can be named, so a later reader resolves either name to the
     * canonical identity. A user echo is named by its otid. A streamed assistant reply is named by
     * whatever id the stream gave it before the sync writer chose the canonical one, and the only
     * reliable test for that is that the two differ - never the shape of the id. Matching a prefix
     * missed every real reply, because the live transport streams `cm-stream-*` while the guard
     * looked for the legacy reducer's `ui-msg-*`.
     *
     * Both writes are self-limiting: the alias is recorded only when the incoming name differs from
     * the canonical one, and [observeIdentityAlias] is a no-op when the evidence already says this.
     * Re-merging an already-canonical row, as a migration replay does, writes nothing.
     */
    private suspend fun TimelineStoreTransaction.indexIdentityAliases(
        incoming: TimelineEvent.Confirmed,
        mergedOtid: String,
        identity: TimelineMessageId,
    ): Boolean {
        val keys = buildList {
            if (incoming.otid.isNotBlank() && incoming.otid != mergedOtid) add("identity/otid/${incoming.otid}")
            if (incoming.messageType == TimelineMessageType.ASSISTANT && incoming.serverId.isNotBlank() &&
                incoming.serverId != identity.value
            ) add("identity/serverId/${incoming.serverId}")
        }
        var indexed = false
        for (key in keys) {
            if (evidence(key, 64 * 1024)?.decodeToString() == identity.value) continue
            putEvidence(key, identity.value.encodeToByteArray())
            indexed = true
        }
        return indexed
    }

    private suspend fun canonicalEventIdentity(reader: TimelineStoreReader, event: TimelineEvent.Confirmed): TimelineMessageId {
        val identity = canonicalIdentity(reader, event.serverId, event.otid)
        if (event.messageType != TimelineMessageType.TOOL_CALL) return identity
        // Legacy live/history projections can give the same invocation different server IDs and otids.
        val owners = event.toolCalls.mapNotNull { call ->
            call.effectiveId.takeIf { it.isNotBlank() }?.let { reader.toolCall(it)?.owner }
        }.distinct()
        check(owners.size <= 1) { "Tool call group has conflicting canonical owners" }
        val owner = owners.singleOrNull() ?: return identity
        check(owner == identity || reader.locate(identity) == null) { "Tool call alias already has a canonical body" }
        return owner
    }

    internal suspend fun canonicalIdentity(reader: TimelineStoreReader, serverId: String, otid: String): TimelineMessageId {
        val alias = otid.takeIf { it.isNotBlank() }?.let {
            reader.evidence("identity/otid/$it", 64 * 1024)?.decodeToString(throwOnInvalidSequence = true)
        } ?: serverId.takeIf { it.isNotBlank() }?.let {
            reader.evidence("identity/serverId/$it", 64 * 1024)?.decodeToString(throwOnInvalidSequence = true)
        }
        return TimelineMessageId(alias ?: serverId)
    }
}
