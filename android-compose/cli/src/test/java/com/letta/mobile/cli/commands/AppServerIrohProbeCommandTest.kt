package com.letta.mobile.cli.commands

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppServerIrohProbeCommandTest {
    @Test
    fun `all scenario expands to every concrete scenario`() {
        val expanded = AppServerIrohProbeCommand.expandScenarios(setOf("all"))

        assertEquals(
            setOf(
                "admin-rpc",
                "idle-send",
                "restart-send",
                "hydrate-heavy",
                "cancel-midstream",
                "no-http",
                "duplicate-send",
            ),
            expanded,
        )
    }

    @Test
    fun `explicit scenarios pass through unchanged`() {
        assertEquals(
            setOf("cancel-midstream", "no-http"),
            AppServerIrohProbeCommand.expandScenarios(setOf("cancel-midstream", "no-http")),
        )
        assertEquals(emptySet<String>(), AppServerIrohProbeCommand.expandScenarios(emptySet()))
    }

    @Test
    fun `accumulator tracks event seq typing terminal and tool pairing`() {
        val accumulator = ProbeAccumulator(turn = 1)
        accumulator.record(
            streamDelta(
                seq = 1,
                delta = buildJsonObject {
                    put("message_type", "reasoning_message")
                    put("id", "r-1")
                    put("run_id", "run-9")
                },
            ),
        )
        accumulator.record(
            streamDelta(
                seq = 2,
                delta = buildJsonObject {
                    put("message_type", "tool_call_message")
                    put("id", "tc-msg")
                    put("run_id", "run-9")
                    put("tool_call", buildJsonObject { put("tool_call_id", "tc-1") })
                },
            ),
        )
        accumulator.record(
            streamDelta(
                seq = 3,
                delta = buildJsonObject {
                    put("message_type", "tool_return_message")
                    put("id", "tr-msg")
                    put("run_id", "run-9")
                    put("tool_call_id", "tc-1")
                },
            ),
        )
        accumulator.record(
            streamDelta(
                seq = 4,
                delta = buildJsonObject {
                    put("message_type", "assistant_message")
                    put("id", "a-1")
                    put("run_id", "run-9")
                    put("content", "hello there")
                },
            ),
        )
        accumulator.record(
            streamDelta(
                seq = 5,
                delta = buildJsonObject {
                    put("message_type", "stop_reason")
                    put("stop_reason", "cancelled")
                    put("run_id", "run-9")
                },
            ),
        )

        val metrics = accumulator.toMetrics(dialMs = 1, firstFrameMs = 1, timedOut = false)

        assertEquals(5, accumulator.recordedFrameCount)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), accumulator.observedEventSeqs)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), metrics.eventSeqs)
        assertEquals(0, metrics.untypedFrameCount)
        assertEquals(1, metrics.turnDoneCount)
        assertEquals("run-9", metrics.activeRunId)
        assertEquals("run-9", metrics.terminalRunId)
        assertEquals("cancelled", metrics.terminalStatus)
        assertEquals(emptyList<String>(), metrics.openToolCallIds)
        assertEquals(0, metrics.framesAfterTerminal)
    }

    @Test
    fun `accumulator flags untyped frames and frames after terminal`() {
        val accumulator = ProbeAccumulator(turn = 1)
        accumulator.record(streamDelta(seq = 1, delta = buildJsonObject { put("content", "no type") }))
        accumulator.record(
            streamDelta(
                seq = 2,
                delta = buildJsonObject {
                    put("message_type", "stop_reason")
                    put("stop_reason", "end_turn")
                    put("status", "completed")
                    put("run_id", "run-1")
                },
            ),
        )
        accumulator.record(
            streamDelta(
                seq = 3,
                delta = buildJsonObject {
                    put("message_type", "assistant_message")
                    put("id", "late")
                    put("content", "after terminal")
                },
            ),
        )

        val metrics = accumulator.toMetrics(dialMs = 1, firstFrameMs = 1, timedOut = false)

        assertEquals(1, metrics.untypedFrameCount)
        assertEquals("completed", metrics.terminalStatus)
        assertEquals(1, metrics.framesAfterTerminal)
    }

    @Test
    fun `accumulator keeps dangling tool call open`() {
        val accumulator = ProbeAccumulator(turn = 1)
        accumulator.record(
            streamDelta(
                seq = 1,
                delta = buildJsonObject {
                    put("message_type", "tool_call_message")
                    put("id", "tc-msg")
                    put("run_id", "run-2")
                    put("tool_call", buildJsonObject { put("tool_call_id", "tc-open") })
                },
            ),
        )
        accumulator.record(
            streamDelta(
                seq = 2,
                delta = buildJsonObject {
                    put("message_type", "stop_reason")
                    put("stop_reason", "cancelled")
                    put("status", "cancelled")
                    put("run_id", "run-2")
                },
            ),
        )

        val metrics = accumulator.toMetrics(dialMs = 1, firstFrameMs = 1, timedOut = false)

        assertEquals(listOf("tc-open"), metrics.openToolCallIds)
    }

    @Test
    fun `accumulator treats error message as failed terminal`() {
        val accumulator = ProbeAccumulator(turn = 1)
        accumulator.record(
            streamDelta(
                seq = 1,
                delta = buildJsonObject {
                    put("message_type", "error_message")
                    put("message", "boom")
                    put("run_id", "run-3")
                },
            ),
        )

        val metrics = accumulator.toMetrics(dialMs = 1, firstFrameMs = 1, timedOut = false)

        assertEquals(1, metrics.turnDoneCount)
        assertEquals("failed", metrics.terminalStatus)
        assertEquals("run-3", metrics.terminalRunId)
        assertTrue(metrics.errorFrames.contains("boom"))
    }

    private fun streamDelta(seq: Long, delta: JsonObject): AppServerInboundFrame.StreamDelta =
        AppServerInboundFrame.StreamDelta(
            runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-1"),
            eventSeq = seq,
            emittedAt = "2026-01-01T00:00:00Z",
            idempotencyKey = "idem-$seq",
            delta = delta,
        )

    @Test
    fun `probe text extraction handles structured content array`() {
        val delta = buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", "hello ")
                    })
                    add(buildJsonObject { put("content", "world") })
                },
            )
        }

        assertEquals("hello world", delta.probeTextContent("content"))
    }

    @Test
    fun `probe text extraction handles structured content object`() {
        val delta = buildJsonObject {
            put(
                "content",
                buildJsonObject {
                    put("value", "object text")
                },
            )
        }

        assertEquals("object text", delta.probeTextContent("content"))
    }

    @Test
    fun `probe text extraction ignores non text objects`() {
        val delta = buildJsonObject {
            put("content", buildJsonObject { put("type", "image") })
        }

        assertEquals("", delta.probeTextContent("content"))
    }
}
