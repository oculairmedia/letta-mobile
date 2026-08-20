package com.letta.mobile.data.timeline.headless

import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
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

    @Test
    fun `long tool-call resume attaches returned result after mid-tool disconnects`() = runTest {
        val baseline = replayWithTimeline(longToolCallStream.map(::recorded))
        val baselineTimeline = compactTimeline(baseline.result.timelineJson)
        assertClean("long-tool baseline", baseline.result)
        assertLongToolCallState("baseline", baseline.timeline)

        listOf(60, 120, 150).forEach { disconnectSecond ->
            val beforeDisconnect = longToolCallStream.filter { it.elapsedSeconds <= disconnectSecond }
            val replayedAfterReconnect = longToolCallStream.filter { it.elapsedSeconds > disconnectSecond }
            assertTrue(
                "disconnect at t+$disconnectSecond should resume the eventual tool return",
                replayedAfterReconnect.any { it.type == "tool_return_message" },
            )

            val interrupted = beforeDisconnect.map(::recorded) +
                replayedAfterReconnect.map(::replayed)

            val result = replayWithTimeline(interrupted)

            assertClean("long-tool disconnect t+$disconnectSecond", result.result)
            assertEquals(
                "long-tool disconnect t+$disconnectSecond should match uninterrupted final state",
                baselineTimeline,
                compactTimeline(result.result.timelineJson),
            )
            assertLongToolCallState("long-tool disconnect t+$disconnectSecond", result.timeline)
        }
    }

    private suspend fun replay(lines: List<String>): HeadlessReplayResult =
        replayWithTimeline(lines).result

    private suspend fun replayWithTimeline(lines: List<String>): ReplayWithTimeline {
        val store = HeadlessTimelineStore()
        val result = HeadlessTimelineReplayer(store).replayJsonl(
            conversationId = CONVERSATION_ID,
            lines = lines.asSequence(),
            assertNoDuplicateUiMessages = true,
            assertOtidUnique = true,
            assertSeqMonotonic = true,
            assertNoOrphanToolReturns = true,
            expectedFinalStatus = "completed",
        )
        return ReplayWithTimeline(
            result = result,
            timeline = store.snapshot(CONVERSATION_ID),
        )
    }

    private fun assertClean(label: String, result: HeadlessReplayResult) {
        assertTrue(
            "$label failed assertions: ${result.assertionReport.failures}",
            result.assertionReport.passed,
        )
    }

    private fun recorded(frame: FrameSpec): String =
        """{"direction":"inbound","frame":${frame.json}}"""

    private fun replayed(frame: FrameSpec): String =
        """{"direction":"inbound","frame":{
            "v":1,"type":"subscribe_frame","id":"sub-${frame.seq}","ts":"${frame.elapsedSeconds.chaosTimestamp()}",
            "run_id":"$RUN_ID","seq":${frame.seq},"frame":${frame.json}
        }}""".trimIndent().replace("\n", "")

    private fun partialRecorded(frame: FrameSpec): String {
        val partialRaw = frame.json.take(frame.json.length / 2)
        return buildJsonObject {
            put("direction", "inbound")
            put("raw", partialRaw)
        }.toString()
    }

    private fun compactTimeline(timelineJson: String): String =
        Json.parseToJsonElement(timelineJson).toString()

    private fun assertLongToolCallState(label: String, timeline: Timeline) {
        val events = timeline.events.filterIsInstance<TimelineEvent.Confirmed>()
        val toolCallEvents = events.filter { event ->
            event.messageType == TimelineMessageType.TOOL_CALL &&
                event.toolCalls.any { it.effectiveId == LONG_TOOL_CALL_ID }
        }
        assertEquals("$label should render one long tool-call event", 1, toolCallEvents.size)

        val toolCallEvent = toolCallEvents.single()
        assertTrue("$label should mark long tool-call decided", toolCallEvent.approvalDecided)
        assertEquals(
            "$label should attach the resumed tool return to the tool-call event",
            LONG_TOOL_RESULT,
            toolCallEvent.toolReturnContentByCallId[LONG_TOOL_CALL_ID],
        )
        assertEquals(
            "$label should not render standalone tool-return events",
            0,
            events.count { it.messageType == TimelineMessageType.TOOL_RETURN },
        )
        assertEquals(
            "$label should render assistant text after the tool return exactly once",
            1,
            events.count {
                it.messageType == TimelineMessageType.ASSISTANT &&
                    it.content == POST_TOOL_ASSISTANT
            },
        )
    }

    private data class ReplayWithTimeline(
        val result: HeadlessReplayResult,
        val timeline: Timeline,
    )

    private data class FrameSpec(
        val seq: Long,
        val elapsedSeconds: Int,
        val type: String,
        val json: String,
    )

    private companion object {
        const val AGENT_ID = "agent-chaos"
        const val CONVERSATION_ID = "conv-chaos"
        const val TURN_ID = "turn-chaos"
        const val RUN_ID = "run-chaos"
        const val LONG_TOOL_CALL_ID = "call-long-chaos"
        const val LONG_TOOL_RESULT = "long lookup completed after 180s"
        const val POST_TOOL_ASSISTANT = "Long lookup finished cleanly."

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

        val longToolCallStream = listOf(
            frame(
                seq = 1,
                elapsedSeconds = 0,
                type = "turn_started",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID"""",
            ),
            frame(
                seq = 2,
                elapsedSeconds = 1,
                type = "assistant_message",
                id = "cm-long-tool-start",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID","content":"Starting long lookup.""""",
            ),
            frame(
                seq = 3,
                elapsedSeconds = 2,
                type = "tool_call_message",
                id = "toolcall-$LONG_TOOL_CALL_ID",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID","tool_call":{"tool_call_id":"$LONG_TOOL_CALL_ID","name":"long_lookup","arguments":"{\"duration_seconds\":180}"}""",
            ),
            frame(
                seq = 4,
                elapsedSeconds = 60,
                type = "reasoning_message",
                id = "reasoning-long-60",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID","reasoning":"still waiting at 60s"""",
            ),
            frame(
                seq = 5,
                elapsedSeconds = 120,
                type = "reasoning_message",
                id = "reasoning-long-120",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID","reasoning":"still waiting at 120s"""",
            ),
            frame(
                seq = 6,
                elapsedSeconds = 150,
                type = "reasoning_message",
                id = "reasoning-long-150",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID","reasoning":"still waiting at 150s"""",
            ),
            frame(
                seq = 7,
                elapsedSeconds = 180,
                type = "tool_return_message",
                id = "toolreturn-$LONG_TOOL_CALL_ID",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID","tool_call_id":"$LONG_TOOL_CALL_ID","tool_return":"$LONG_TOOL_RESULT"""",
            ),
            frame(
                seq = 8,
                elapsedSeconds = 181,
                type = "assistant_message",
                id = "cm-long-tool-finished",
                extra = """"agent_id":"$AGENT_ID","conversation_id":"$CONVERSATION_ID","turn_id":"$TURN_ID","run_id":"$RUN_ID","content":"$POST_TOOL_ASSISTANT"""",
            ),
            frame(
                seq = 9,
                elapsedSeconds = 182,
                type = "turn_done",
                extra = """"turn_id":"$TURN_ID","run_id":"$RUN_ID","status":"completed"""",
            ),
        )

        private fun frame(
            seq: Long,
            elapsedSeconds: Int = seq.toInt(),
            type: String,
            id: String = "$type-$seq",
            extra: String,
        ): FrameSpec =
            FrameSpec(
                seq = seq,
                elapsedSeconds = elapsedSeconds,
                type = type,
                json = """{"v":1,"type":"$type","id":"$id","ts":"${elapsedSeconds.chaosTimestamp()}",$extra,"seq":$seq}""",
            )
    }
}

private fun Int.chaosTimestamp(): String {
    val minutes = this / 60
    val seconds = this % 60
    return "2026-05-27T00:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}Z"
}
