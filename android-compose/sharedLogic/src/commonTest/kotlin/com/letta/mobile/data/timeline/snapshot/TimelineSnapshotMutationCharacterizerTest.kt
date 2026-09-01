package com.letta.mobile.data.timeline.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineSnapshotMutationCharacterizerTest {
    private val scope = TimelineScope("backend", "conversation", "agent")

    @Test
    fun characterizesDeterministicLargeSnapshotMutationsWithCompactSummaries() {
        val initial = envelope(events = fixtureEvents())
        val initialSummary = TimelineSnapshotMutationCharacterizer.summarize(initial)
        assertEquals(2_000, initialSummary.events.size)
        assertTrue(initialSummary.events.all { it.structuralPrimary != 0L || it.structuralSecondary != 0L })

        assertShape(
            TimelineSnapshotMutationCharacterizer.characterize(null, initialSummary),
            inserted = 2_000,
            previousCount = 0,
            currentCount = 2_000,
            maxComparisons = 2_000,
        )

        val appended = envelope(events = initial.events + event(2_000))
        val appendedSummary = TimelineSnapshotMutationCharacterizer.summarize(appended)
        assertShape(
            TimelineSnapshotMutationCharacterizer.characterize(initialSummary, appendedSummary),
            inserted = 1,
            previousCount = 2_000,
            currentCount = 2_001,
            maxComparisons = 4_001,
        )

        val updated = envelope(events = initial.events.mapIndexed { index, event ->
            if (index == 1_000) {
                event.copy(
                    approvalDecided = true,
                    approvalRequestId = "approval-updated",
                    approvalDecision = "APPROVED",
                    toolReturnContent = "updated-return",
                    toolReturnIsError = true,
                    toolReturnContentByCallId = mapOf("call-updated" to "return"),
                    toolReturnIsErrorByCallId = mapOf("call-updated" to true),
                    toolReturnTruncationByCallId = mapOf(
                        "call-updated" to StoredToolReturnTruncation("return-updated", 42L),
                    ),
                    attachments = listOf(
                        StoredImageAttachmentPointer("image/png", 12L, "https://example.invalid/image"),
                    ),
                )
            } else {
                event
            }
        })
        assertShape(
            TimelineSnapshotMutationCharacterizer.characterize(
                initialSummary,
                TimelineSnapshotMutationCharacterizer.summarize(updated),
            ),
            updated = 1,
            previousCount = 2_000,
            currentCount = 2_000,
            maxComparisons = 4_000,
        )

        val prefixDeleted = envelope(events = initial.events.drop(25))
        assertShape(
            TimelineSnapshotMutationCharacterizer.characterize(
                initialSummary,
                TimelineSnapshotMutationCharacterizer.summarize(prefixDeleted),
            ),
            deleted = 25,
            moved = 1_975,
            previousCount = 2_000,
            currentCount = 1_975,
            maxComparisons = 3_975,
        )

        val reordered = envelope(events = initial.events.toMutableList().also { events ->
            val first = events.removeAt(0)
            events.add(first)
        })
        assertShape(
            TimelineSnapshotMutationCharacterizer.characterize(
                initialSummary,
                TimelineSnapshotMutationCharacterizer.summarize(reordered),
            ),
            moved = 2_000,
            previousCount = 2_000,
            currentCount = 2_000,
            maxComparisons = 4_000,
        )

        val cursorOnly = initial.copy(liveCursor = "cursor-next")
        assertShape(
            TimelineSnapshotMutationCharacterizer.characterize(
                initialSummary,
                TimelineSnapshotMutationCharacterizer.summarize(cursorOnly),
            ),
            cursorChanged = true,
            previousCount = 2_000,
            currentCount = 2_000,
            maxComparisons = 4_000,
        )

        assertShape(
            TimelineSnapshotMutationCharacterizer.characterize(initialSummary, initialSummary),
            noOp = true,
            previousCount = 2_000,
            currentCount = 2_000,
            maxComparisons = 4_000,
        )
    }

    @Test
    fun marksMissingAndDuplicateIdentitiesAsUnclassifiable() {
        val valid = event(1)
        val missingBothIds = valid.copy(serverId = "", otid = "")
        val duplicate = valid.copy(content = "duplicate content")

        val missingShape = TimelineSnapshotMutationCharacterizer.characterize(
            TimelineSnapshotMutationCharacterizer.summarize(envelope(events = listOf(valid))),
            TimelineSnapshotMutationCharacterizer.summarize(envelope(events = listOf(missingBothIds))),
        )
        assertTrue(missingShape.unclassifiable > 0)
        assertFalse(missingShape.noOp)
        assertEquals(0, missingShape.fullEnvelopeEncodes)
        assertTrue(missingShape.eventComparisons <= 2)

        val duplicateShape = TimelineSnapshotMutationCharacterizer.characterize(
            TimelineSnapshotMutationCharacterizer.summarize(envelope(events = listOf(valid))),
            TimelineSnapshotMutationCharacterizer.summarize(envelope(events = listOf(valid, duplicate))),
        )
        assertTrue(duplicateShape.unclassifiable > 0)
        assertFalse(duplicateShape.noOp)
        assertEquals(0, duplicateShape.fullEnvelopeEncodes)
        assertTrue(duplicateShape.eventComparisons <= 3)
    }

    private fun assertShape(
        shape: SnapshotMutationShape,
        inserted: Int = 0,
        updated: Int = 0,
        deleted: Int = 0,
        moved: Int = 0,
        cursorChanged: Boolean = false,
        noOp: Boolean = false,
        previousCount: Int,
        currentCount: Int,
        maxComparisons: Int,
    ) {
        assertEquals(inserted, shape.inserted)
        assertEquals(updated, shape.updated)
        assertEquals(deleted, shape.deleted)
        assertEquals(moved, shape.moved)
        assertEquals(cursorChanged, shape.cursorMetadataChanged)
        assertEquals(noOp, shape.noOp)
        assertEquals(0, shape.unclassifiable)
        assertEquals(previousCount, shape.previousCount)
        assertEquals(currentCount, shape.currentCount)
        assertTrue(shape.eventComparisons <= maxComparisons)
        assertEquals(0, shape.fullEnvelopeEncodes)
    }

    private fun envelope(
        events: List<StoredTimelineEvent>,
        liveCursor: String? = "cursor",
    ) = StoredTimelineEnvelope(
        scope = scope,
        revision = 1L,
        liveCursor = liveCursor,
        events = events,
    )

    private fun fixtureEvents(): List<StoredTimelineEvent> = List(2_000, ::event)

    private fun event(index: Int) = StoredTimelineEvent(
        position = index.toDouble(),
        otid = "otid-$index",
        content = "content-$index",
        serverId = "server-$index",
        messageType = "TOOL_CALL",
        dateIso = "2026-08-24T12:00:00Z",
        runId = "run-${index / 10}",
        stepId = "step-$index",
        agentId = "agent",
        seqId = index,
        toolCalls = listOf(StoredToolCall("call-$index", "tool", "{\"index\":$index}")),
        toolReturnContentByCallId = mapOf("call-$index" to "return-$index"),
    )
}
