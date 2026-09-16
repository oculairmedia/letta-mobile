package com.letta.mobile.data.timeline.snapshot

/** Compact, content-free snapshot comparison baseline. The first successful write establishes it. */
data class SnapshotStructuralSummary(
    val schemaVersion: Int,
    val scopeFingerprint: Long,
    val liveCursorFingerprint: Long,
    val backfillCursorFingerprint: Long,
    val releasedOlderCount: Int,
    val events: List<SnapshotEventSummary>,
)

data class SnapshotEventSummary(
    val order: Int,
    val positionBits: Long,
    val identityPrimary: Long,
    val identitySecondary: Long,
    val structuralPrimary: Long,
    val structuralSecondary: Long,
)

data class SnapshotMutationShape(
    val inserted: Int,
    val updated: Int,
    val deleted: Int,
    val moved: Int,
    val cursorMetadataChanged: Boolean,
    val noOp: Boolean,
    val unclassifiable: Int,
    val previousCount: Int,
    val currentCount: Int,
    val eventComparisons: Int,
    val fullEnvelopeEncodes: Int = 0,
)

object TimelineSnapshotMutationCharacterizer {
    fun summarize(envelope: StoredTimelineEnvelope): SnapshotStructuralSummary = SnapshotStructuralSummary(
        schemaVersion = envelope.schemaVersion,
        scopeFingerprint = fingerprint { mixString(envelope.scope.backendId); mixString(envelope.scope.agentId); mixString(envelope.scope.conversationId) },
        liveCursorFingerprint = fingerprint { mixString(envelope.liveCursor) },
        backfillCursorFingerprint = fingerprint { mixString(envelope.backfillCursor) },
        releasedOlderCount = envelope.releasedOlderCount,
        events = envelope.events.mapIndexed { order, event ->
            SnapshotEventSummary(
                order = order,
                positionBits = event.position.toBits(),
                identityPrimary = identityFingerprint(event, 0L),
                identitySecondary = identityFingerprint(event, 0x9e3779b97f4a7c15UL.toLong()),
                structuralPrimary = structuralFingerprint(event, 0L),
                structuralSecondary = structuralFingerprint(event, 0x517cc1b727220a95UL.toLong()),
            )
        },
    )

    fun characterize(previous: SnapshotStructuralSummary?, current: SnapshotStructuralSummary): SnapshotMutationShape {
        if (previous == null) return SnapshotMutationShape(
            inserted = current.events.size, updated = 0, deleted = 0, moved = 0,
            cursorMetadataChanged = false, noOp = current.events.isEmpty(), unclassifiable = 0,
            previousCount = 0, currentCount = current.events.size, eventComparisons = current.events.size,
        )
        val previousIndex = index(previous.events)
        val currentIndex = index(current.events)
        val ambiguous = previousIndex.ambiguous + currentIndex.ambiguous
        val cursorChanged = cursorChanged(previous, current)
        if (ambiguous > 0) return SnapshotMutationShape(
            0, 0, 0, 0, cursorChanged, false, ambiguous,
            previous.events.size, current.events.size, previous.events.size + current.events.size,
        )
        var inserted = 0
        var updated = 0
        var deleted = 0
        var moved = 0
        currentIndex.events.forEach { (key, event) ->
            val old = previousIndex.events[key]
            if (old == null) inserted++ else {
                if (old.structuralPrimary != event.structuralPrimary || old.structuralSecondary != event.structuralSecondary) updated++
                if (old.order != event.order || old.positionBits != event.positionBits) moved++
            }
        }
        previousIndex.events.keys.forEach { if (it !in currentIndex.events) deleted++ }
        return SnapshotMutationShape(
            inserted, updated, deleted, moved, cursorChanged,
            inserted == 0 && updated == 0 && deleted == 0 && moved == 0 && !cursorChanged,
            0, previous.events.size, current.events.size, previous.events.size + current.events.size,
        )
    }

    private fun cursorChanged(previous: SnapshotStructuralSummary, current: SnapshotStructuralSummary): Boolean =
        previous.schemaVersion != current.schemaVersion || previous.scopeFingerprint != current.scopeFingerprint ||
            previous.liveCursorFingerprint != current.liveCursorFingerprint ||
            previous.backfillCursorFingerprint != current.backfillCursorFingerprint ||
            previous.releasedOlderCount != current.releasedOlderCount

    private data class IdentityKey(val primary: Long, val secondary: Long)
    private data class Index(val events: Map<IdentityKey, SnapshotEventSummary>, val ambiguous: Int)

    private fun index(events: List<SnapshotEventSummary>): Index {
        val result = LinkedHashMap<IdentityKey, SnapshotEventSummary>()
        var ambiguous = 0
        events.forEach { event ->
            val key = IdentityKey(event.identityPrimary, event.identitySecondary)
            if (key.primary == 0L && key.secondary == 0L || result.put(key, event) != null) ambiguous++
        }
        return Index(result, ambiguous)
    }

    private fun identityFingerprint(event: StoredTimelineEvent, seed: Long): Long {
        val serverId = event.serverId.takeIf { it.isNotBlank() }
        val otid = event.otid.takeIf { it.isNotBlank() }
        if (serverId == null && otid == null) return 0L
        return fingerprint(seed) {
            // Either canonical transport identity is sufficient; fields prevent cross-kind collisions.
            mixString(serverId)
            mixString(otid)
            mixString(event.messageType)
            mixString(event.runId)
            mixString(event.stepId)
            mix(event.seqId?.toLong() ?: -1L)
        }
    }

    private fun structuralFingerprint(event: StoredTimelineEvent, seed: Long): Long =
        StoredEnvelopeFingerprint.Hasher(seed).apply { mixEvent(event, includePosition = false) }.value

    private fun fingerprint(seed: Long = 0L, block: StoredEnvelopeFingerprint.Hasher.() -> Unit): Long =
        StoredEnvelopeFingerprint.Hasher(seed).apply(block).value
}
