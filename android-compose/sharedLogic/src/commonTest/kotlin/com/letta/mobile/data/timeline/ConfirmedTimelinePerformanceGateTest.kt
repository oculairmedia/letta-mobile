package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import com.letta.mobile.data.timeline.snapshot.TimelineSnapshotCodec
import kotlinx.collections.immutable.toPersistentList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ConfirmedTimelinePerformanceGateTest {

    private fun createSyntheticConfirmedTimeline(eventCount: Int): Timeline {
        val events = ArrayList<TimelineEvent.Confirmed>(eventCount)
        for (i in 0 until eventCount) {
            val isUser = i % 2 == 0
            val event = TimelineEvent.Confirmed(
                position = i.toDouble(),
                otid = "otid-$i",
                content = "Message content payload number $i with detailed context and text.",
                serverId = "srv-$i",
                messageType = if (isUser) TimelineMessageType.USER else TimelineMessageType.ASSISTANT,
                date = timelineNow(),
                runId = "run-${i / 10}",
                stepId = "step-$i",
                agentId = "agent-1",
                seqId = i,
                toolCalls = if (!isUser && i % 4 == 1) {
                    listOf(
                        ToolCall(id = "tc-$i", name = "read_data", arguments = """{"query":"$i"}"""),
                    ).toPersistentList()
                } else {
                    kotlinx.collections.immutable.persistentListOf()
                },
                approvalRequestId = null,
                approvalDecided = false,
                approvalDecision = null,
                toolReturnContent = if (!isUser && i % 4 == 1) "Tool output result for step $i" else null,
                toolReturnIsError = false,
                toolReturnContentByCallId = kotlinx.collections.immutable.persistentMapOf(),
                toolReturnIsErrorByCallId = kotlinx.collections.immutable.persistentMapOf(),
                toolReturnTruncationByCallId = kotlinx.collections.immutable.persistentMapOf(),
                attachments = kotlinx.collections.immutable.persistentListOf(),
                source = MessageSource.LETTA_SERVER,
            )
            events.add(event)
        }
        return Timeline(
            conversationId = "conv-perf",
            events = events.toPersistentList(),
            liveCursor = "live-cursor-$eventCount",
            backfillCursor = "backfill-cursor-0",
            releasedOlderCount = 0,
        )
    }

    @Test
    fun fingerprintAndEncodeCompleteFor50_150_500Events() {
        // wasmJs ChromeHeadless on GitHub runners is much slower than JVM/Native.
        // Keep tight budgets for the 50/150 cases; give 500-event fingerprint/
        // encode enough headroom that CI load does not flake the required
        // shared-multiplatform check (observed: fingerprint 500 over 10s).
        val fingerprintBudgets = mapOf(50 to 2.seconds, 150 to 4.seconds, 500 to 40.seconds)
        val encodeBudgets = mapOf(50 to 5.seconds, 150 to 12.seconds, 500 to 90.seconds)
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-perf")

        for (count in fingerprintBudgets.keys) {
            val timeline = createSyntheticConfirmedTimeline(count)
            val envelope = TimelineSnapshotCodec.timelineToStoredEnvelope(
                timeline = timeline,
                scope = scope,
                revision = 1L,
            )

            repeat(10) {
                TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(envelope)
                TimelineSnapshotCodec.encode(envelope)
            }

            var fingerprint = 0L
            val fingerprintStarted = TimeSource.Monotonic.markNow()
            repeat(50) {
                fingerprint = TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(envelope)
            }
            val fingerprintElapsed = fingerprintStarted.elapsedNow()

            var encodedLength = 0
            val encodeStarted = TimeSource.Monotonic.markNow()
            repeat(50) {
                encodedLength = TimelineSnapshotCodec.encode(envelope).length
            }
            val encodeElapsed = encodeStarted.elapsedNow()

            assertTrue(fingerprint != 0L)
            assertTrue(encodedLength > 0)
            assertTrue(
                fingerprintElapsed < fingerprintBudgets.getValue(count),
                "Fingerprinting $count events took $fingerprintElapsed",
            )
            assertTrue(
                encodeElapsed < encodeBudgets.getValue(count),
                "Encoding $count events took $encodeElapsed",
            )
        }
    }

    @Test
    fun fingerprintRemainsStableAcrossSnapshotRoundTrip() {
        val timeline1 = createSyntheticConfirmedTimeline(150)
        val scope = TimelineScope(backendId = "test-backend", conversationId = "conv-perf")
        val envelope1 = TimelineSnapshotCodec.timelineToStoredEnvelope(timeline1, scope, revision = 1L)
        val decodedTimeline = TimelineSnapshotCodec.storedEnvelopeToTimeline(envelope1)

        assertEquals(timeline1.events.size, decodedTimeline.events.size)
        val fp1 = TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(envelope1)
        val envelope2 = TimelineSnapshotCodec.timelineToStoredEnvelope(decodedTimeline, scope, revision = 2L)
        val fp2 = TimelineSnapshotCodec.computeStoredEnvelopeFingerprint(envelope2)

        assertEquals(fp1, fp2)
    }
}
