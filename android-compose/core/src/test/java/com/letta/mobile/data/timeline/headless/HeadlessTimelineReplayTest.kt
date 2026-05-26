package com.letta.mobile.data.timeline.headless

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
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

    private fun recorded(frameJson: String): String =
        """{"direction":"inbound","frame":$frameJson}"""
}
