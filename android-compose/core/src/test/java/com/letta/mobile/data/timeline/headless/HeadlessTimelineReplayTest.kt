package com.letta.mobile.data.timeline.headless

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class HeadlessTimelineReplayTest {
    @Test
    fun `replay folds recorded ws frames through timeline reducer`() = runTest {
        val lines = sequenceOf(
            recorded("""{"v":1,"type":"welcome","id":"w1","ts":"2026-05-26T00:00:00Z","server_id":"srv","session_id":"sess"}"""),
            recorded("""{"v":1,"type":"assistant_message","id":"cm-stream-1","ts":"2026-05-26T00:00:01Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-1","run_id":"run-1","content":"Hello","seq_id":1}"""),
            recorded("""{"v":1,"type":"assistant_message","id":"cm-stream-1","ts":"2026-05-26T00:00:02Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-1","run_id":"run-1","content":"Hello world","seq_id":2}"""),
        )

        val result = HeadlessTimelineReplayer().replayJsonl(
            conversationId = "conv-1",
            lines = lines,
            assertNoDuplicateUiMessages = true,
            assertOtidUnique = true,
            assertSeqMonotonic = true,
        )

        result.framesSeen shouldBe 3
        result.messagesIngested shouldBe 2
        result.assertionReport.passed shouldBe true
        result.assertionReport.eventCount shouldBe 1
        result.timelineJson.contains("Hello world") shouldBe true
    }

    @Test
    fun `replay captures timeline snapshots after selected frames`() = runTest {
        val lines = sequenceOf(
            recorded("""{"v":1,"type":"welcome","id":"w1","ts":"2026-05-26T00:00:00Z","server_id":"srv","session_id":"sess"}"""),
            recorded("""{"v":1,"type":"assistant_message","id":"cm-stream-1","ts":"2026-05-26T00:00:01Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-1","run_id":"run-1","content":"Hello","seq_id":1}"""),
            recorded("""{"v":1,"type":"assistant_message","id":"cm-stream-1","ts":"2026-05-26T00:00:02Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-1","run_id":"run-1","content":"Hello world","seq_id":2}"""),
        )

        val result = HeadlessTimelineReplayer().replayJsonl(
            conversationId = "conv-1",
            lines = lines,
            dumpOptions = HeadlessReplayDumpOptions(dumpAfterFrame = 1, dumpFrames = setOf(2)),
        )

        result.frameSnapshots.map { it.frameIndex } shouldBe listOf(1, 2)
        result.frameSnapshots[0].timeline["eventCount"]?.jsonPrimitive?.content shouldBe "1"
        result.frameSnapshots[1].timeline.toString().contains("Hello world") shouldBe true
        Json.parseToJsonElement(result.frameSnapshotsJson()).jsonArray.size shouldBe 2
    }

    @Test
    fun `replay captures every nonblank frame when dump each frame is enabled`() = runTest {
        val lines = sequenceOf(
            "",
            recorded("""{"v":1,"type":"welcome","id":"w1","ts":"2026-05-26T00:00:00Z","server_id":"srv","session_id":"sess"}"""),
            recorded("""{"v":1,"type":"assistant_message","id":"cm-stream-1","ts":"2026-05-26T00:00:01Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-1","run_id":"run-1","content":"Hello","seq_id":1}"""),
        )

        val result = HeadlessTimelineReplayer().replayJsonl(
            conversationId = "conv-1",
            lines = lines,
            dumpOptions = HeadlessReplayDumpOptions(dumpAfterEachFrame = true),
        )

        result.frameSnapshots.map { it.frameType } shouldBe listOf("welcome", "assistant_message")
    }

    @Test
    fun `replay assertion catches non monotonic recorded seq ids`() = runTest {
        val lines = sequenceOf(
            recorded("""{"v":1,"type":"assistant_message","id":"cm-stream-2","ts":"2026-05-26T00:00:01Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-1","run_id":"run-1","content":"second","seq_id":2}"""),
            recorded("""{"v":1,"type":"assistant_message","id":"cm-stream-3","ts":"2026-05-26T00:00:02Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-1","run_id":"run-1","content":"first","seq_id":1}"""),
        )

        val result = HeadlessTimelineReplayer().replayJsonl(
            conversationId = "conv-1",
            lines = lines,
            assertSeqMonotonic = true,
        )

        result.assertionReport.passed shouldBe false
        result.assertionReport.failures shouldContain
            "non-monotonic recorded seq for run run-1: 2, 1"
    }

    @Test
    fun `ka770 replay fixture detects duplicate assistant body in one run`() = runTest {
        val lines = requireNotNull(
            javaClass.classLoader?.getResourceAsStream("replay/ka770-duplicate-assistant.jsonl")
        ).bufferedReader().use { it.lineSequence().toList() }

        val result = HeadlessTimelineReplayer().replayJsonl(
            conversationId = "conv-ka770",
            lines = lines.asSequence(),
            assertNoDuplicateUiMessages = true,
            assertOtidUnique = true,
            assertSeqMonotonic = true,
        )

        result.assertionReport.passed shouldBe false
        result.assertionReport.failures shouldContain
            "duplicate UiMessage semantic keys: ASSISTANT|run-ka770|The final assistant body appears once."
    }

    @Test
    fun `extended replay assertions catch empty body and prefix orphan shapes`() = runTest {
        val lines = sequenceOf(
            recorded("""{"v":1,"type":"assistant_message","id":"cm-empty","ts":"2026-05-26T00:00:01Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-1","run_id":"run-1","content":"","seq_id":1}"""),
            recorded("""{"v":1,"type":"assistant_message","id":"cm-prefix","ts":"2026-05-26T00:00:02Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-1","run_id":"run-1","content":"Hello","seq_id":2}"""),
            recorded("""{"v":1,"type":"assistant_message","id":"cm-full","ts":"2026-05-26T00:00:03Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-1","run_id":"run-1","content":"Hello world","seq_id":3}"""),
        )

        val result = HeadlessTimelineReplayer().replayJsonl(
            conversationId = "conv-1",
            lines = lines,
            assertNoEmptyBodies = true,
            assertNoPrefixOrphans = true,
            expectedUiMessageCountPerRun = 1,
        )

        result.assertionReport.passed shouldBe false
        result.assertionReport.failures shouldContain "empty UiMessage body in run run-1: cm-empty"
        result.assertionReport.failures shouldContain
            "prefix orphan UiMessage in run run-1: cm-prefix is a strict prefix of cm-full"
        result.assertionReport.failures shouldContain "run run-1 has 3 UiMessages; expected 1"
    }

    @Test
    fun `extended replay assertions check final status and orphan tool returns`() = runTest {
        val lines = sequenceOf(
            recorded("""{"v":1,"type":"tool_return_message","id":"return-1","ts":"2026-05-26T00:00:01Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-1","run_id":"run-1","tool_call_id":"call-missing","tool_return":"ok"}"""),
            recorded("""{"v":1,"type":"turn_done","id":"done-1","ts":"2026-05-26T00:00:02Z","turn_id":"turn-1","run_id":"run-1","status":"failed"}"""),
        )

        val result = HeadlessTimelineReplayer().replayJsonl(
            conversationId = "conv-1",
            lines = lines,
            expectedFinalStatus = "completed",
            assertNoOrphanToolReturns = true,
        )

        result.assertionReport.passed shouldBe false
        result.assertionReport.failures shouldContain
            "final run status failed does not match expected completed"
        result.assertionReport.failures shouldContain
            "orphan tool_return return-1 in run run-1: tool_call_id=call-missing"
    }

    @Test
    fun `run shape assertions catch incomplete runs and abandoned tool calls`() = runTest {
        val lines = sequenceOf(
            recorded("""{"v":1,"type":"turn_started","id":"start-1","ts":"2026-05-26T00:00:00Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-1","run_id":"run-open"}"""),
            recorded("""{"v":1,"type":"assistant_message","id":"cm-open","ts":"2026-05-26T00:00:01Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-1","run_id":"run-open","content":"still running","seq_id":1}"""),
            recorded("""{"v":1,"type":"tool_call_message","id":"toolcall-1","ts":"2026-05-26T00:00:02Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-2","run_id":"run-done","tool_call":{"tool_call_id":"call-1","name":"Bash","arguments":"{}"}}"""),
            recorded("""{"v":1,"type":"turn_done","id":"done-1","ts":"2026-05-26T00:00:03Z","turn_id":"turn-2","run_id":"run-done","status":"completed"}"""),
        )

        val result = HeadlessTimelineReplayer().replayJsonl(
            conversationId = "conv-1",
            lines = lines,
            assertRunCompletes = true,
            assertNoAbandonedToolCalls = true,
        )

        result.assertionReport.passed shouldBe false
        result.assertionReport.failures shouldContain
            "run run-open did not reach terminal status (last status=<none>)"
        result.assertionReport.failures shouldContain
            "abandoned tool_call call-1 in run run-done: toolcall-1 reached completed"
    }

    @Test
    fun `run shape assertions catch approval run mismatch and otid drift`() = runTest {
        val lines = sequenceOf(
            recorded("""{"v":1,"type":"approval_request_message","id":"toolcall-approval","ts":"2026-05-26T00:00:00Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-approval","run_id":"run-approval","tool_call":{"tool_call_id":"approval-call","name":"Bash","arguments":"{}"},"otid":"stable-1"}"""),
            recorded("""{"v":1,"type":"approval_request_message","id":"toolcall-approval","ts":"2026-05-26T00:00:01Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-approval","run_id":"run-approval","tool_call":{"tool_call_id":"approval-call","name":"Bash","arguments":"{}"},"otid":"stable-2"}"""),
            recorded("""{"v":1,"type":"tool_return_message","id":"return-approval","ts":"2026-05-26T00:00:02Z","agent_id":"agent-1","conversation_id":"conv-1","turn_id":"turn-primary","run_id":"run-primary","tool_call_id":"approval-call","tool_return":"ok"}"""),
        )

        val result = HeadlessTimelineReplayer().replayJsonl(
            conversationId = "conv-1",
            lines = lines,
            assertApprovalToolReturnOnApprovalRun = true,
            assertOtidStableAcrossRetry = true,
        )

        result.assertionReport.passed shouldBe false
        result.assertionReport.failures shouldContain
            "approval tool_return return-approval for tool_call_id=approval-call landed on run run-primary instead of approval run run-approval"
        result.assertionReport.failures shouldContain
            "message approval_request_message/toolcall-approval observed with multiple otids: stable-1, stable-2"
    }

    private fun recorded(frameJson: String): String =
        """{"direction":"inbound","frame":$frameJson}"""
}
