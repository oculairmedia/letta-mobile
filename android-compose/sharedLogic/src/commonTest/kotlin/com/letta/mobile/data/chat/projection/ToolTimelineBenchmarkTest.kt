package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
// TimeSource.Monotonic, not System.nanoTime(): commonTest also compiles for the
// hostNative target, where java.lang.System does not exist.
import kotlin.time.TimeSource

/**
 * letta-mobile-8kdjm.13: Deterministic benchmark and correctness test suite for tool timeline projection.
 *
 * Fixture shape:
 * - 100 settled messages (80 text messages + 20 messages with tool calls = 20 tool timeline groups)
 * - 1 active run message (streaming tool call updates)
 * - Diverse payload types: short output, 50k-char large output, pending approval request, error status.
 */
class ToolTimelineBenchmarkTest {

    private fun createBenchmarkMessages(): MutableList<UiMessage> {
        val messages = ArrayList<UiMessage>()
        var toolGroupCount = 0

        // 100 settled messages
        for (i in 0 until 100) {
            val msgId = "msg-settled-$i"
            val isToolMsg = (i % 5 == 0) // 20 tool messages out of 100
            val toolCalls = if (isToolMsg) {
                toolGroupCount++
                when (toolGroupCount) {
                    1 -> listOf(
                        // Short output
                        UiToolCall(
                            name = "read_file",
                            arguments = """{"path":"src/Main.kt"}""",
                            result = "fun main() = println(\"Hello\")",
                            status = "success",
                            toolCallId = "call-short-$i",
                        )
                    )
                    2 -> listOf(
                        // Large 50,000-character output
                        UiToolCall(
                            name = "exec_cmd",
                            arguments = """{"command":"gradle build --debug"}""",
                            result = "LOG LINE: " + "X".repeat(50_000),
                            status = "success",
                            toolCallId = "call-large-$i",
                        )
                    )
                    3 -> listOf(
                        // Pending approval request
                        UiToolCall(
                            name = "delete_database",
                            arguments = """{"db":"production"}""",
                            result = null,
                            status = null,
                            toolCallId = "call-approval-$i",
                        )
                    )
                    4 -> listOf(
                        // Error output
                        UiToolCall(
                            name = "connect_server",
                            arguments = """{"host":"10.0.0.1"}""",
                            result = "Connection refused: ETIMEDOUT 10.0.0.1:8080",
                            status = "failed",
                            toolCallId = "call-error-$i",
                        )
                    )
                    else -> listOf(
                        UiToolCall(
                            name = "tool_step_$i",
                            arguments = """{"step":$i}""",
                            result = "Result step $i",
                            status = "success",
                            toolCallId = "call-step-$i",
                        )
                    )
                }
            } else null

            val approvalReq = if (isToolMsg && toolGroupCount == 3) {
                UiApprovalRequest(
                    requestId = "appr-req-$i",
                    toolCalls = listOf(
                        UiApprovalToolCall(
                            toolCallId = "call-approval-$i",
                            name = "delete_database",
                            arguments = """{"db":"production"}""",
                        )
                    )
                )
            } else null

            messages.add(
                UiMessage(
                    id = msgId,
                    role = if (isToolMsg) "assistant" else "user",
                    content = if (isToolMsg) "" else "User message text $i",
                    timestamp = (10000 + i).toString(),
                    toolCalls = toolCalls,
                    approvalRequest = approvalReq,
                )
            )
        }

        // 1 active run message with tool call in progress
        messages.add(
            UiMessage(
                id = "msg-active-run",
                role = "assistant",
                content = "",
                timestamp = "20000",
                runId = "run-active-101",
                toolCalls = listOf(
                    UiToolCall(
                        name = "active_tool_step",
                        arguments = """{"frame":0}""",
                        result = null,
                        status = "running",
                        toolCallId = "call-active-101",
                    )
                )
            )
        )

        return messages
    }

    @Test
    fun testNoDuplicateKeysInBenchmarkProjection() {
        val messages = createBenchmarkMessages()
        val projector = ToolTimelineProjector()
        val groups = projector.project(messages)

        assertEquals(21, groups.size) // 20 settled groups + 1 active group

        val groupKeys = groups.map { it.key }
        assertEquals(groupKeys.size, groupKeys.toSet().size, "Duplicate group key detected!")

        val callKeys = groups.flatMap { it.calls }.map { it.key }
        assertEquals(callKeys.size, callKeys.toSet().size, "Duplicate call key detected!")
    }

    @Test
    fun testSettledOuterGroupsRetainReferentialIdentity() {
        val messages = createBenchmarkMessages()
        val projector = ToolTimelineProjector()

        val pass1Groups = projector.project(messages)

        // Simulate 20 streaming updates to active run ONLY (message index 100)
        for (frame in 1..20) {
            val activeMsg = messages.last()
            val updatedActiveMsg = activeMsg.copy(
                toolCalls = listOf(
                    UiToolCall(
                        name = "active_tool_step",
                        arguments = """{"frame":$frame}""",
                        result = if (frame == 20) "Streaming finished" else null,
                        status = if (frame == 20) "success" else "running",
                        toolCallId = "call-active-101",
                    )
                )
            )
            messages[messages.lastIndex] = updatedActiveMsg

            val currentGroups = projector.project(messages)
            assertEquals(21, currentGroups.size)

            // Verify all 20 settled groups retained referential identity (assertSame)
            for (gIdx in 0 until 20) {
                assertSame(pass1Groups[gIdx], currentGroups[gIdx], "Settled group $gIdx lost referential identity on frame $frame")
            }
        }
    }

    @Test
    fun testOnlyActiveGroupRecomposes() {
        val messages = createBenchmarkMessages()
        val projector = ToolTimelineProjector()

        val pass1Groups = projector.project(messages)

        // Update active run message
        val activeMsg = messages.last()
        val updatedActiveMsg = activeMsg.copy(
            toolCalls = listOf(
                UiToolCall(
                    name = "active_tool_step",
                    arguments = """{"frame":1}""",
                    result = "Partial token output...",
                    status = "running",
                    toolCallId = "call-active-101",
                )
            )
        )
        messages[messages.lastIndex] = updatedActiveMsg

        val pass2Groups = projector.project(messages)

        // 20 settled groups are identical instances
        for (gIdx in 0 until 20) {
            assertSame(pass1Groups[gIdx], pass2Groups[gIdx])
        }

        // Active group (index 20) was re-projected
        assertTrue(pass1Groups[20] !== pass2Groups[20], "Active group was not re-projected upon update")
        assertEquals("Partial token output...", pass2Groups[20].calls.single().result)
    }

    // NOTE: "collapsed output avoids parse work" is deliberately NOT asserted here.
    // The collapsed preview (deferredToolResultPreview) lives in feature-chat and is not
    // reachable from sharedLogic commonTest, so a test here could only re-implement the
    // logic inline and time its own copy — which would keep passing even if production
    // regressed to parsing while collapsed. That criterion is covered against the real
    // code path by feature-chat's ToolMonospacePresentationTest (bead .9).

    @Test
    fun testDeterministicProjectionBenchmarkAndP95() {
        val messages = createBenchmarkMessages()
        val projector = ToolTimelineProjector()

        // Warmup JIT
        repeat(20) {
            projector.project(messages)
        }

        val iterations = 100
        val durationsNs = LongArray(iterations)

        for (i in 0 until iterations) {
            // Update active run message
            val activeMsg = messages.last()
            messages[messages.lastIndex] = activeMsg.copy(
                toolCalls = listOf(
                    UiToolCall(
                        name = "active_tool_step",
                        arguments = """{"frame":$i}""",
                        result = "Streaming frame output $i",
                        status = "running",
                        toolCallId = "call-active-101",
                    )
                )
            )

            val start = TimeSource.Monotonic.markNow()
            val groups = projector.project(messages)
            val elapsed = start.elapsedNow().inWholeNanoseconds

            durationsNs[i] = elapsed
            assertEquals(21, groups.size)
        }

        val sortedMs = durationsNs.map { it / 1_000_000.0 }.sorted()
        val minMs = sortedMs.first()
        val maxMs = sortedMs.last()
        val medianMs = sortedMs[iterations / 2]
        val p95Index = (iterations * 0.95).toInt().coerceAtMost(iterations - 1)
        val p95Ms = sortedMs[p95Index]
        val p99Index = (iterations * 0.99).toInt().coerceAtMost(iterations - 1)
        val p99Ms = sortedMs[p99Index]
        val meanMs = sortedMs.average()

        println("ToolTimelineProjection Benchmark Results ($iterations iterations over 101 messages / 21 groups / 21 calls):")
        println("  Min:    ${minMs.format(3)} ms")
        println("  Median: ${medianMs.format(3)} ms")
        println("  Mean:   ${meanMs.format(3)} ms")
        println("  p95:    ${p95Ms.format(3)} ms")
        println("  p99:    ${p99Ms.format(3)} ms")
        println("  Max:    ${maxMs.format(3)} ms")

        // Assertion: p95 <= 4ms
        assertTrue(p95Ms <= 4.0, "Tool timeline projection p95 benchmark failed: p95 was ${p95Ms}ms (target <= 4.0ms)")
    }

    private fun Double.format(digits: Int): String {
        val factor = when (digits) {
            1 -> 10.0
            2 -> 100.0
            3 -> 1000.0
            else -> 1000.0
        }
        val rounded = kotlin.math.round(this * factor) / factor
        return rounded.toString()
    }
}
