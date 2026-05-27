package com.letta.mobile.data.timeline.headless

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class HeadlessTimelineResumeChaosTest {
    @Test
    fun `resume replay matches baseline after every frame-boundary disconnect`() = runTest {
        val baseline = replay(syntheticStream.map(::recorded))
        val baselineTimeline = compactTimeline(baseline.timelineJson)

        (0..syntheticStream.size).forEach { cut ->
            val interrupted = syntheticStream.take(cut).map(::recorded) +
                syntheticStream.drop(cut).map(::replayed)

            val result = replay(interrupted)

            assertClean("frame-boundary cut=$cut", result)
            assertEquals(
                "frame-boundary cut=$cut should match uninterrupted final state",
                baselineTimeline,
                compactTimeline(result.timelineJson),
            )
            assertEquals(
                "frame-boundary cut=$cut should ingest the same message count",
                baseline.messagesIngested,
                result.messagesIngested,
            )
        }
    }

    @Test
    fun `resume replay matches baseline when disconnect drops a partial frame`() = runTest {
        val baseline = replay(syntheticStream.map(::recorded))
        val baselineTimeline = compactTimeline(baseline.timelineJson)

        syntheticStream.indices.forEach { partialIndex ->
            val dropped = syntheticStream[partialIndex]
            val interrupted = syntheticStream.take(partialIndex).map(::recorded) +
                partialRecorded(dropped) +
                syntheticStream.drop(partialIndex).map(::replayed)

            val result = replay(interrupted)

            assertClean("partial-frame index=$partialIndex", result)
            assertEquals(
                "partial-frame index=$partialIndex should match uninterrupted final state",
                baselineTimeline,
                compactTimeline(result.timelineJson),
            )
            assertEquals(
                "partial-frame index=$partialIndex should include one ignored invalid frame",
                1,
                result.ignoredFrameTypes["<invalid>"],
            )
        }
    }

    private suspend fun replay(lines: List<String>): HeadlessReplayResult =
        HeadlessTimelineReplayer().replayJsonl(
            conversationId = CONVERSATION_ID,
            lines = lines.asSequence(),
            assertNoDuplicateUiMessages = true,
            assertOtidUnique = true,
            assertSeqMonotonic = true,
            assertNoOrphanToolReturns = true,
            expectedFinalStatus = "completed",
        )

    private fun assertClean(label: String, result: HeadlessReplayResult) {
        assertTrue(
            "$label failed assertions: ${result.assertionReport.failures}",
            result.assertionReport.passed,
        )
    }

    private fun recorded(frame: FrameSpec): String =
        """{"direction":"inbound","frame":${frame.json}}"""

    private fun replayed(frame: FrameSpec): String =
        recorded(
            FrameSpec(
                seq = frame.seq,
                json = """
                    {"v":1,"type":"subscribe_frame","id":"sub-${frame.seq}","ts":"2026-05-27T00:00:${frame.seq.padded()}Z",
                     "run_id":"$RUN_ID","seq":${frame.seq},"frame":${frame.json}}
                """.trimIndent().replace("\n", ""),
            )
        )

    private fun partialRecorded(frame: FrameSpec): String {
        val partialRaw = frame.json.take(frame.json.length / 2)
        return buildJsonObject {
            put("direction", "inbound")
            put("raw", partialRaw)
        }.toString()
    }

    private fun compactTimeline(timelineJson: String): String =
        Json.parseToJsonElement(timelineJson).toString()

    private fun Long.padded(): String = toString().padStart(2, '0')

    private data class FrameSpec(
        val seq: Long,
        val json: String,
    )

    private companion object {
        const val AGENT_ID = "agent-chaos"
        const val CONVERSATION_ID = "conv-chaos"
        const val TURN_ID = "turn-chaos"
        const val RUN_ID = "run-chaos"

        val syntheticStream = listOf(
            frame(
                seq = 1,
                type = "turn_started",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID"""",
            ),
            frame(
                seq = 2,
                type = "assistant_message",
                id = "cm-stream-chaos-1",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID","content":"chunk 1"""",
            ),
            frame(
                seq = 3,
                type = "assistant_message",
                id = "cm-stream-chaos-2",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID","content":"chunk 2"""",
            ),
            frame(
                seq = 4,
                type = "tool_call_message",
                id = "toolcall-call-chaos",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID","tool_call":{"tool_call_id":"call-chaos","name":"lookup","arguments":"{}"}""",
            ),
            frame(
                seq = 5,
                type = "tool_return_message",
                id = "toolreturn-call-chaos",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID","tool_call_id":"call-chaos","tool_return":"ok"""",
            ),
            frame(
                seq = 6,
                type = "reasoning_message",
                id = "reasoning-chaos",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID","reasoning":"verified lookup output"""",
            ),
            frame(
                seq = 7,
                type = "assistant_message",
                id = "cm-stream-chaos-3",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID","content":"chunk 3"""",
            ),
            frame(
                seq = 8,
                type = "turn_done",
                extra = """"turn_id":"$TURN_ID","run_id":"$RUN_ID","status":"completed"""",
            ),
        )

        private fun frame(
            seq: Long,
            type: String,
            id: String = "$type-$seq",
            extra: String,
        ): FrameSpec =
            FrameSpec(
                seq = seq,
                json = """{"v":1,"type":"$type","id":"$id","ts":"2026-05-27T00:00:${seq.toString().padStart(2, '0')}Z",$extra,"seq":$seq}""",
            )
    }
}
