package com.letta.mobile.ui.screens.chat

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRenderModelBuilderTest {

    @Test
    fun `reasoning followed by assistant with same content skips assistant echo`() {
        val messages = listOf(
            reasoning("r1", content = "thinking"),
            assistant("a1", content = "thinking"),
        )

        assertEquals(listOf("r1"), dedupeReasoningAssistantEchoes(messages).map { it.id })
    }

    @Test
    fun `reasoning followed by assistant with different content keeps both`() {
        val messages = listOf(
            reasoning("r1", content = "thinking"),
            assistant("a1", content = "answer"),
        )

        assertEquals(listOf("r1", "a1"), dedupeReasoningAssistantEchoes(messages).map { it.id })
    }

    @Test
    fun `non-reasoning intervening message resets reasoning echo dedupe`() {
        val messages = listOf(
            reasoning("r1", content = "thinking"),
            user("u1", content = "interrupt"),
            assistant("a1", content = "thinking"),
        )

        assertEquals(listOf("r1", "u1", "a1"), dedupeReasoningAssistantEchoes(messages).map { it.id })
    }

    @Test
    fun `assistant echo is skipped only for exact content match`() {
        val messages = listOf(
            reasoning("r1", content = "Thinking"),
            assistant("a1", content = "thinking"),
            reasoning("r2", content = "done"),
            assistant("a2", content = "done "),
        )

        assertEquals(listOf("r1", "a1", "r2", "a2"), dedupeReasoningAssistantEchoes(messages).map { it.id })
    }

    @Test
    fun `empty tool-call batch is not dropped as reasoning echo`() {
        val messages = listOf(
            reasoning("r1", content = ""),
            assistantToolCall(
                id = "tc1",
                toolCalls = listOf(
                    UiToolCall(name = "Bash", arguments = "{\"command\":\"a\"}", result = null),
                    UiToolCall(name = "Bash", arguments = "{\"command\":\"b\"}", result = null),
                ),
            ),
        )

        assertEquals(listOf("r1", "tc1"), dedupeReasoningAssistantEchoes(messages).map { it.id })
    }

    @Test
    fun `simple mode keeps user assistant and errors but filters reasoning`() {
        val messages = listOf(
            user("u1"),
            reasoning("r1"),
            assistant("a1"),
            toolError("e1"),
        )

        val visible = filterMessagesForMode(messages, ChatDisplayMode.Simple)

        assertEquals(listOf("u1", "a1", "e1"), visible.map { it.id })
    }

    @Test
    fun `simple mode filters assistant-scoped tool calls`() {
        val messages = listOf(
            user("u1"),
            assistantToolCall("tc1"),
            assistant("a1", content = "final answer"),
        )

        val visible = filterMessagesForMode(messages, ChatDisplayMode.Simple)

        assertEquals(listOf("u1", "a1"), visible.map { it.id })
    }

    @Test
    fun `interactive and debug keep the same message set`() {
        val messages = listOf(
            user("u1"),
            reasoning("r1"),
            assistant("a1"),
            toolError("e1"),
        )

        assertEquals(messages, filterMessagesForMode(messages, ChatDisplayMode.Interactive))
        assertEquals(messages, filterMessagesForMode(messages, ChatDisplayMode.Debug))
    }

    @Test
    fun `string chat mode maps simple and debug explicitly and unknown to interactive`() {
        assertEquals(ChatDisplayMode.Simple, "simple".toChatDisplayMode())
        assertEquals(ChatDisplayMode.Debug, "debug".toChatDisplayMode())
        assertEquals(ChatDisplayMode.Interactive, "interactive".toChatDisplayMode())
        assertEquals(ChatDisplayMode.Interactive, "anything-else".toChatDisplayMode())
    }

    @Test
    fun `render model exposes visible and grouped chronological messages`() {
        val model = buildChatRenderModel(
            messages = listOf(user("u1"), user("u2"), assistant("a1")),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals(listOf("u1", "u2", "a1"), model.visibleMessages.map { it.id })
        assertEquals(
            listOf(GroupPosition.First, GroupPosition.Last, GroupPosition.None),
            model.groupedMessages.map { it.second },
        )
    }

    @Test
    fun `render model adds latency to final assistant response from previous user timestamp`() {
        val model = buildChatRenderModel(
            messages = listOf(
                user("u1", ts = "2026-04-19T12:00:00Z"),
                assistant("a1", ts = "2026-04-19T12:00:02.500Z"),
                assistant("a2", ts = "2026-04-19T12:00:04Z"),
            ),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals(null, model.visibleMessages.first { it.id == "a1" }.latencyMs)
        assertEquals(4000L, model.visibleMessages.first { it.id == "a2" }.latencyMs)
    }

    @Test
    fun `render model puts tool run latency on final assistant response`() {
        val model = buildChatRenderModel(
            messages = listOf(
                user("u1", ts = "2026-04-19T12:00:00Z"),
                assistantToolCall("tc1", ts = "2026-04-19T12:00:01Z"),
                assistantToolCall("tc2", ts = "2026-04-19T12:00:02Z"),
                assistant("a1", content = "final answer", ts = "2026-04-19T12:00:06Z"),
            ),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals(null, model.visibleMessages.first { it.id == "tc1" }.latencyMs)
        assertEquals(null, model.visibleMessages.first { it.id == "tc2" }.latencyMs)
        assertEquals(6000L, model.visibleMessages.first { it.id == "a1" }.latencyMs)
    }

    @Test
    fun `render model preserves explicit latency metadata`() {
        val explicitLatency = assistant("a1", ts = "2026-04-19T12:00:02Z").copy(latencyMs = 42L)
        val model = buildChatRenderModel(
            messages = listOf(user("u1", ts = "2026-04-19T12:00:00Z"), explicitLatency),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals(42L, model.visibleMessages.first { it.id == "a1" }.latencyMs)
    }

    @Test
    fun `render items are newest first for reverse layout`() {
        val model = buildChatRenderModel(
            messages = listOf(
                user("u1", ts = "2026-04-19T12:00:00Z"),
                assistant("a1", ts = "2026-04-19T12:00:01Z"),
                user("u2", ts = "2026-04-19T12:00:02Z"),
            ),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals(listOf("msg-u2", "msg-a1", "msg-u1"), model.renderItems.map { it.key })
    }

    @Test
    fun `duplicate message IDs are filtered before render item shaping`() {
        val model = buildChatRenderModel(
            messages = listOf(
                user("dup", content = "first"),
                assistant("a1"),
                user("dup", content = "second"),
            ),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals(listOf("msg-a1", "msg-dup"), model.renderItems.map { it.key })
        val duplicateItem = model.renderItems[1] as ChatRenderItem.Single
        assertEquals("first", duplicateItem.message.content)
    }

    @Test
    fun `adjacent assistant messages with same runId produce newest-first RunBlock item`() {
        val model = buildChatRenderModel(
            messages = listOf(
                user("u1", ts = "2026-04-19T11:59:59Z"),
                assistant("a1", runId = "r1", ts = "2026-04-19T12:00:00Z"),
                assistant("a2", runId = "r1", ts = "2026-04-19T12:00:30Z"),
            ),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals(2, model.renderItems.size)
        val block = model.renderItems.first() as ChatRenderItem.RunBlock
        assertEquals("run-r1", block.key)
        assertEquals("2026-04-19T12:00:30Z", block.boundaryTimestamp)
        assertEquals(listOf("a1", "a2"), block.messages.map { it.first.id })
        assertEquals(listOf(GroupPosition.First, GroupPosition.Last), block.messages.map { it.second })
        assertTrue(block.containsMessageId("a1"))
        assertTrue(block.containsMessageId("a2"))
    }

    @Test
    fun `single assistant run keeps stable run key through full pipeline`() {
        val model = buildChatRenderModel(
            messages = listOf(user("u1"), assistant("a1", runId = "r1")),
            mode = ChatDisplayMode.Interactive,
        )

        assertEquals("run-r1", model.renderItems.first().key)
    }

    @Test
    fun `render model build records bounded timings for large streaming histories`() {
        val oneThousand = streamingHistory(turnCount = 200)
        val fiveThousand = streamingHistory(turnCount = 1_000)

        val oneThousandStats = measureRenderModelBuild(oneThousand)
        val fiveThousandStats = measureRenderModelBuild(fiveThousand)

        assertEquals(1_000, oneThousand.size)
        assertEquals(5_000, fiveThousand.size)
        assertTrue(oneThousandStats.p50Micros > 0)
        assertTrue(oneThousandStats.p95Micros >= oneThousandStats.p50Micros)
        assertTrue(fiveThousandStats.p50Micros > 0)
        assertTrue(fiveThousandStats.p95Micros >= fiveThousandStats.p50Micros)

        val model = buildChatRenderModel(fiveThousand, ChatDisplayMode.Interactive)
        val keys = model.renderItems.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertFalse(model.visibleMessages.any { it.id.startsWith("assistant-echo-") })

        println(
            "buildChatRenderModel profile: " +
                "1k p50=${oneThousandStats.p50Micros}us p95=${oneThousandStats.p95Micros}us; " +
                "5k p50=${fiveThousandStats.p50Micros}us p95=${fiveThousandStats.p95Micros}us"
        )
    }

    // letta-mobile-a7ij follow-up: optimistic-twin collapse in the render
    // dedupe layer. These tests pin the safety-net behavior for the SSE
    // wins-the-race / postHandlerCollapse-misses scenario that produced the
    // duplicate-initial-message bug reported via screenshot.

    @Test
    fun `optimistic-twin collapse drops client-prefixed user message when confirmed twin present`() {
        val grouped = listOf(
            user("client-abc", content = "hello") to GroupPosition.First,
            user("server-xyz", content = "hello") to GroupPosition.Last,
        )

        val deduped = dedupeGroupedMessagesForLazyKeys(grouped)

        assertEquals(listOf("server-xyz"), deduped.map { it.first.id })
    }

    @Test
    fun `optimistic-twin collapse drops cm-prefixed assistant message when confirmed twin present`() {
        val grouped = listOf(
            assistant("cm-stream-1", content = "answer text") to GroupPosition.First,
            assistant("server-final-1", content = "answer text") to GroupPosition.Last,
        )

        val deduped = dedupeGroupedMessagesForLazyKeys(grouped)

        assertEquals(listOf("server-final-1"), deduped.map { it.first.id })
    }

    @Test
    fun `optimistic-twin collapse drops bootstrap-initial-duplicate user message`() {
        val grouped = listOf(
            user(
                "client-user-initial-duplicate-1234",
                content = "What is the meaning of life",
            ) to GroupPosition.First,
            user(
                "server-msg-9000",
                content = "What is the meaning of life",
            ) to GroupPosition.Last,
        )

        val deduped = dedupeGroupedMessagesForLazyKeys(grouped)

        assertEquals(listOf("server-msg-9000"), deduped.map { it.first.id })
    }

    @Test
    fun `optimistic-twin collapse preserves legitimate quick-fire repeats from server`() {
        val grouped = listOf(
            user("server-a", content = "ping") to GroupPosition.First,
            user("server-b", content = "ping") to GroupPosition.Last,
        )

        val deduped = dedupeGroupedMessagesForLazyKeys(grouped)

        // Both server-issued — leave intact. Users do sometimes send the same
        // message twice (network retry, impatient tap) and that must remain
        // visible.
        assertEquals(listOf("server-a", "server-b"), deduped.map { it.first.id })
    }

    @Test
    fun `optimistic-twin collapse does not fuse different roles`() {
        val grouped = listOf(
            user("client-1", content = "same content") to GroupPosition.First,
            assistant("server-1", content = "same content") to GroupPosition.Last,
        )

        val deduped = dedupeGroupedMessagesForLazyKeys(grouped)

        assertEquals(listOf("client-1", "server-1"), deduped.map { it.first.id })
    }

    @Test
    fun `optimistic-twin collapse does not fuse reasoning with assistant final`() {
        val grouped = listOf(
            reasoning("client-r-1", content = "deliberating") to GroupPosition.First,
            assistant("server-a-1", content = "deliberating") to GroupPosition.Last,
        )

        val deduped = dedupeGroupedMessagesForLazyKeys(grouped)

        // Reasoning↔assistant fusion is handled by dedupeReasoningAssistantEchoes,
        // not the optimistic-twin pass. Keep both so that upstream pass owns
        // the decision.
        assertEquals(listOf("client-r-1", "server-a-1"), deduped.map { it.first.id })
    }

    @Test
    fun `optimistic-twin collapse keeps earliest when both sides are optimistic`() {
        val grouped = listOf(
            assistant("cm-stream-early", content = "answer") to GroupPosition.First,
            assistant("cm-stream-late", content = "answer") to GroupPosition.Last,
        )

        val deduped = dedupeGroupedMessagesForLazyKeys(grouped)

        // Stable ordering when neither has a confirmed identity yet — the
        // earlier emission wins so render keys don't oscillate during a
        // recompose storm.
        assertEquals(listOf("cm-stream-early"), deduped.map { it.first.id })
    }

    @Test
    fun `optimistic-twin collapse ignores tool-call assistant messages`() {
        val grouped = listOf(
            assistantToolCall(id = "client-tc-1") to GroupPosition.First,
            assistantToolCall(id = "server-tc-1") to GroupPosition.Last,
        )

        val deduped = dedupeGroupedMessagesForLazyKeys(grouped)

        // Tool-call dedupe is the responsibility of the run/timeline compactor
        // (compactRunToolCallSteps) — this safety net must not interfere.
        assertEquals(listOf("client-tc-1", "server-tc-1"), deduped.map { it.first.id })
    }

    @Test
    fun `optimistic-twin collapse tolerates whitespace differences`() {
        val grouped = listOf(
            user("client-trim-1", content = "  hello world  ") to GroupPosition.First,
            user("server-trim-1", content = "hello world") to GroupPosition.Last,
        )

        val deduped = dedupeGroupedMessagesForLazyKeys(grouped)

        assertEquals(listOf("server-trim-1"), deduped.map { it.first.id })
    }

    @Test
    fun `optimistic-twin collapse preserves non-adjacent matches`() {
        val grouped = listOf(
            user("client-1", content = "hello") to GroupPosition.First,
            assistant("server-mid", content = "hi back") to GroupPosition.Middle,
            user("server-1", content = "hello") to GroupPosition.Last,
        )

        val deduped = dedupeGroupedMessagesForLazyKeys(grouped)

        // Non-adjacent twins are intentional repeats in a real conversation
        // (user repeating themselves across turns). Only adjacent twins
        // indicate the optimistic-Local-orphan race.
        assertEquals(listOf("client-1", "server-mid", "server-1"), deduped.map { it.first.id })
    }

    private fun user(
        id: String,
        content: String = "u-$id",
        ts: String = "2026-04-19T12:00:00Z",
    ) = UiMessage(
        id = id,
        role = "user",
        content = content,
        timestamp = ts,
    )

    private fun assistant(
        id: String,
        content: String = "a-$id",
        runId: String? = null,
        ts: String = "2026-04-19T12:00:00Z",
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = ts,
        runId = runId,
    )

    private fun assistantToolCall(
        id: String,
        ts: String = "2026-04-19T12:00:00Z",
        toolCalls: List<UiToolCall> = listOf(
            UiToolCall(
                name = "Bash",
                arguments = "{\"command\":\"pwd\"}",
                result = "/tmp\n",
            )
        ),
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = "",
        timestamp = ts,
        toolCalls = toolCalls,
    )

    private fun reasoning(
        id: String,
        content: String = "reasoning-$id",
        ts: String = "2026-04-19T12:00:00Z",
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = ts,
        isReasoning = true,
    )

    private fun toolError(
        id: String,
        content: String = "error-$id",
        ts: String = "2026-04-19T12:00:00Z",
    ) = UiMessage(
        id = id,
        role = "tool",
        content = content,
        timestamp = ts,
        isError = true,
    )

    private data class RenderBuildStats(
        val p50Micros: Long,
        val p95Micros: Long,
    )

    private fun measureRenderModelBuild(messages: List<UiMessage>): RenderBuildStats {
        repeat(5) { buildChatRenderModel(messages, ChatDisplayMode.Interactive) }
        val samples = List(30) {
            val started = System.nanoTime()
            buildChatRenderModel(messages, ChatDisplayMode.Interactive)
            (System.nanoTime() - started) / 1_000L
        }.sorted()
        return RenderBuildStats(
            p50Micros = samples.percentile(0.50),
            p95Micros = samples.percentile(0.95),
        )
    }

    private fun List<Long>.percentile(percentile: Double): Long {
        val index = ((size - 1) * percentile).toInt().coerceIn(indices)
        return this[index]
    }

    private fun streamingHistory(turnCount: Int): List<UiMessage> = buildList(capacity = turnCount * 5) {
        repeat(turnCount) { index ->
            val runId = "run-$index"
            val timestamp = "2026-04-19T12:00:${(index % 60).toString().padStart(2, '0')}Z"
            add(user("user-$index", content = "prompt $index", ts = timestamp))
            add(reasoning("reasoning-$index", content = "thinking $index", ts = timestamp))
            add(assistant("assistant-echo-$index", content = "thinking $index", runId = runId, ts = timestamp))
            add(
                assistantToolCall(
                    id = "tools-$index",
                    ts = timestamp,
                    toolCalls = listOf(
                        UiToolCall(name = "Bash", arguments = "{\"command\":\"pwd\"}", result = "/tmp\n"),
                        UiToolCall(name = "Read", arguments = "{\"file\":\"README.md\"}", result = "content"),
                    ),
                ).copy(runId = runId)
            )
            add(assistant("answer-$index", content = "answer $index", runId = runId, ts = timestamp))
        }
    }
}
