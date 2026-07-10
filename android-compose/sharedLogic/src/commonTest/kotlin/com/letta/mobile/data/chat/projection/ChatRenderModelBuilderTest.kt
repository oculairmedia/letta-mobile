package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.chat.projection.deduplicateRenderItemsByMessageId
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.common.GroupPosition
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.time.TimeSource

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
    fun `incremental render item cache reuses committed history for tail appends and replacements`() {
        val cache = IncrementalChatRenderItemsCache()
        val base = streamingHistory(turnCount = 1_000)
        cache.renderItems(base, ChatDisplayMode.Interactive, ChatMessageListChange.Full)

        val appended = base + assistant("tail-append", content = "stream chunk", runId = "run-999")
        val afterAppend = cache.renderItems(appended, ChatDisplayMode.Interactive, ChatMessageListChange.AppendTail)
        val oldestCommittedItem = afterAppend.last()

        val replaced = base + assistant("tail-append", content = "stream chunk plus more", runId = "run-999")
        val afterReplace = cache.renderItems(replaced, ChatDisplayMode.Interactive, ChatMessageListChange.ReplaceTail)

        assertEquals(1, cache.fullBuildCount)
        assertEquals(2, cache.incrementalBuildCount)
        assertSame(oldestCommittedItem, afterReplace.last())
        assertEquals(
            buildChatRenderModel(replaced, ChatDisplayMode.Interactive).renderItems.map { it.key },
            afterReplace.map { it.key },
        )
    }

    @Test
    fun `letta-mobile-vxase incremental tail never produces duplicate render keys across the split`() {
        // The committed history ends with a RunBlock for run-shared, and the
        // active tail (after the latest user turn) ALSO contains assistant
        // messages for run-shared. Each slice de-dupes within itself, so a
        // naive tailRenderItems + committedRenderItems concatenation yields
        // TWO items keyed "run-run-shared" -> LazyColumn "Key already used"
        // crash on the first render after restart. The builder must globally
        // de-dupe the joined list.
        // Same runId BEFORE the latest user turn (lands in committed history)
        // AND after it (lands in the active tail). activeTailStartIndex splits
        // just before the latest user, so a0(run-shared) sits in committed and
        // a1(run-shared) in the tail — each slice emits a "run-run-shared"
        // RunBlock, colliding when concatenated.
        val cache = IncrementalChatRenderItemsCache()
        val base = listOf(
            assistant("a0", content = "earlier reply", runId = "run-shared", ts = "2026-04-19T12:00:00Z"),
            user("u1"),
            assistant("a1", content = "tail reply", runId = "run-shared", ts = "2026-04-19T12:01:00Z"),
            user("u2"),
            assistant("a2", content = "newest reply", runId = "run-shared", ts = "2026-04-19T12:02:00Z"),
        )
        cache.renderItems(base, ChatDisplayMode.Interactive, ChatMessageListChange.Full)

        val appended = base + assistant(
            "a3",
            content = "newest reply continues",
            runId = "run-shared",
            ts = "2026-04-19T12:02:30Z",
        )
        val result = cache.renderItems(appended, ChatDisplayMode.Interactive, ChatMessageListChange.AppendTail)

        val keys = result.map { it.key }
        // The crash invariant: LazyColumn keys must be globally unique. Before
        // the fix this list contained two "run-run-shared" entries and threw
        // "Key was already used".
        assertEquals(
            keys.size,
            keys.toSet().size,
            "render keys must be globally unique across the tail/committed split; got duplicates: " +
                keys.groupingBy { it }.eachCount().filterValues { it > 1 },
        )
    }

    @Test
    fun `letta-mobile-g87l6 streaming tail tick reuses identity of every settled render item`() {
        // letta-mobile-g87l6 (desktop -> mobile flicker). During a streaming turn
        // the observer projects a ReplaceTail per fanned-out token. The
        // incremental cache reuses the FROZEN committed history, but
        // activeTailStartIndex pulls the latest user prompt AND the immediately
        // preceding assistant turn into the rebuilt "active tail", so those
        // already-settled bubbles get a FRESH ChatRenderItem instance on every
        // token even though their content never changes. In Compose a new item
        // instance for the same LazyColumn key recomposes the whole subtree —
        // that per-token recomposition of settled bubbles is the visible flicker.
        //
        // Contract: on a ReplaceTail streaming tick, ONLY the render item that
        // actually changed (the streaming tail whose text grew) may get a new
        // instance. Every settled item — including the just-sent user prompt and
        // the previous turn's completed answer — must keep its object identity so
        // Compose skips recomposing it.
        val cache = IncrementalChatRenderItemsCache()
        // Prior settled turn + a just-sent user prompt. This is the state right
        // before the observed assistant reply begins streaming.
        val history = listOf(
            user("u-prev", content = "previous question", ts = "2026-04-19T12:00:00Z"),
            assistant("a-prev", content = "previous answer", runId = "run-prev", ts = "2026-04-19T12:00:01Z"),
            user("u-now", content = "current question", ts = "2026-04-19T12:00:02Z"),
        )
        fun streamingFrame(text: String) =
            history + assistant("a-stream", content = text, runId = "run-now", ts = "2026-04-19T12:00:03Z")

        // Faithful observer sequence: history hydrates (Full), then the observed
        // assistant reply's first token APPENDS (AppendTail), then each fanned-out
        // token REPLACES the tail (ReplaceTail) — the per-token flicker path.
        cache.renderItems(history, ChatDisplayMode.Interactive, ChatMessageListChange.Full)
        var prev = cache.renderItems(streamingFrame("A"), ChatDisplayMode.Interactive, ChatMessageListChange.AppendTail)
        val settledKeys = prev.drop(1).map { it.key } // everything except the streaming tail (index 0, reverse layout)

        // Stream several more tokens as ReplaceTail (the observer per-token path).
        for (text in listOf("An", "Ans", "Answ", "Answe", "Answer")) {
            val next = cache.renderItems(streamingFrame(text), ChatDisplayMode.Interactive, ChatMessageListChange.ReplaceTail)
            val prevByKey = prev.associateBy { it.key }
            val reidentifiedSettled = settledKeys.filter { key ->
                val before = prevByKey.getValue(key)
                val after = next.first { it.key == key }
                before !== after
            }
            assertEquals(
                emptyList(),
                reidentifiedSettled,
                "settled render items must keep their identity across a streaming ReplaceTail tick " +
                    "(text='$text'); these got a fresh instance and will recompose/flicker: $reidentifiedSettled",
            )
            prev = next
        }
    }

    @Test
    fun `incremental render item cache falls back when update is not an active tail change`() {
        val cache = IncrementalChatRenderItemsCache()
        val base = streamingHistory(turnCount = 200)
        cache.renderItems(base, ChatDisplayMode.Interactive, ChatMessageListChange.Full)

        val prepended = listOf(user("older-page")) + base
        cache.renderItems(prepended, ChatDisplayMode.Interactive, ChatMessageListChange.AppendTail)

        assertEquals(2, cache.fullBuildCount)
        assertEquals(0, cache.incrementalBuildCount)
    }

    @Test
    fun `long history active-tail render frame budget avoids full rebuilds`() {
        val cache = IncrementalChatRenderItemsCache()
        val prefix = streamingHistory(turnCount = 100)
        var frameMessages = prefix + assistant("stream-tail", content = "token 0", runId = "run-999")
        cache.renderItems(frameMessages, ChatDisplayMode.Interactive, ChatMessageListChange.Full)

        val samplesMicros = buildList {
            repeat(10) { frame ->
                frameMessages = prefix + assistant(
                    id = "stream-tail",
                    content = "token $frame ${"x".repeat(frame + 1)}",
                    runId = "run-999",
                )
                val started = TimeSource.Monotonic.markNow()
                cache.renderItems(frameMessages, ChatDisplayMode.Interactive, ChatMessageListChange.ReplaceTail)
                add(started.elapsedNow().inWholeMicroseconds)
            }
        }

        assertEquals(1, cache.fullBuildCount)
        assertEquals(10, cache.incrementalBuildCount)
        val maxMicros = samplesMicros.maxOrNull() ?: 0L
        assertTrue(maxMicros > 0)
        println(
            "incremental active-tail render frame budget: " +
                "frames=${samplesMicros.size} max=${maxMicros}us " +
                "p95=${samplesMicros.sorted().percentile(0.95)}us"
        )
    }

    @Test
    fun `render model build records bounded timings for large streaming histories`() {
        val oneThousand = streamingHistory(turnCount = 40)
        val fiveThousand = streamingHistory(turnCount = 120)

        val oneThousandStats = measureRenderModelBuild(oneThousand)
        val fiveThousandStats = measureRenderModelBuild(fiveThousand)

        assertEquals(200, oneThousand.size)
        assertEquals(600, fiveThousand.size)
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

        // Both server-issued â€” leave intact. Users do sometimes send the same
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

        // Reasoningâ†”assistant fusion is handled by dedupeReasoningAssistantEchoes,
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

        // Stable ordering when neither has a confirmed identity yet â€” the
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
        // (compactRunToolCallSteps) â€” this safety net must not interfere.
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

    @Test
    fun `deduplicateRenderItemsByMessageId drops a Single whose id is owned by a RunBlock`() {
        // letta-mobile-x1xnl: incremental tail/committed join can emit the same
        // assistant message as a Single (key msg-<id>) AND inside a RunBlock
        // (key run-<runId>). Different keys => key dedup misses => stranded
        // duplicate on screen. Collapse by underlying message id.
        val a1 = assistant("a1", runId = "r1")
        val a2 = assistant("a2", runId = "r1")
        val staleSingle = ChatRenderItem.Single(message = a1, groupPosition = GroupPosition.None)
        val runBlock = ChatRenderItem.RunBlock(
            runId = "r1",
            messages = listOf(a1 to GroupPosition.First, a2 to GroupPosition.Last),
        )

        val out = deduplicateRenderItemsByMessageId(listOf(runBlock, staleSingle))

        assertEquals(1, out.size)
        assertTrue(out.single() is ChatRenderItem.RunBlock)
    }

    @Test
    fun `deduplicateRenderItemsByMessageId drops a Single ADJACENT to a RunBlock with the same runId`() {
        // letta-mobile-x1xnl (on-device root cause): the streaming reply renders
        // as a RunBlock (key run-<runId>) in one rebuild, then the SAME turn's
        // reconciled final renders as a standalone Single with a DIFFERENT server
        // message id (key msg-<newId>) in a later Full rebuild. They share no
        // message id but DO share the runId, so both used to render (the stranded
        // fragment). Collapse the Single into the RunBlock by runId.
        val streamed = assistant("a1", runId = "local-run-107")
        val streamed2 = assistant("a2", runId = "local-run-107")
        val reconciledFinal = assistant("a1-final", runId = "local-run-107")
        val runBlock = ChatRenderItem.RunBlock(
            runId = "local-run-107",
            messages = listOf(streamed to GroupPosition.First, streamed2 to GroupPosition.Last),
        )
        val strandedSingle = ChatRenderItem.Single(message = reconciledFinal, groupPosition = GroupPosition.None)

        val out = deduplicateRenderItemsByMessageId(listOf(runBlock, strandedSingle))

        assertEquals(1, out.size)
        assertTrue(out.single() is ChatRenderItem.RunBlock)
    }

    @Test
    fun `deduplicateRenderItemsByMessageId keeps a non-assistant Single sharing a RunBlock runId`() {
        // #824 review (P2): an ERROR/status bubble (role != assistant) can carry
        // the same runId as an assistant RunBlock but is a distinct bubble — it
        // must NOT be collapsed by runId.
        val a1 = assistant("a1", runId = "r9")
        val a2 = assistant("a2", runId = "r9")
        val runBlock = ChatRenderItem.RunBlock(
            runId = "r9",
            messages = listOf(a1 to GroupPosition.First, a2 to GroupPosition.Last),
        )
        val errorBubble = ChatRenderItem.Single(
            message = UiMessage(id = "err-1", role = "system", content = "Error", timestamp = "2026-04-19T12:00:00Z", runId = "r9"),
            groupPosition = GroupPosition.None,
        )

        val out = deduplicateRenderItemsByMessageId(listOf(runBlock, errorBubble))

        assertEquals(2, out.size)
        assertTrue(out.any { it is ChatRenderItem.Single && (it as ChatRenderItem.Single).message.id == "err-1" })
    }

    @Test
    fun `deduplicateRenderItemsByMessageId keeps a NON-ADJACENT Single sharing a RunBlock runId`() {
        // #824 review (P1): a run can be split across history by user turns, so an
        // assistant Single from the same run elsewhere in the list is a REAL older
        // message — only the streaming/final duplicate is adjacent. Do not drop a
        // non-adjacent same-run Single.
        val a1 = assistant("a1", runId = "rshared")
        val a2 = assistant("a2", runId = "rshared")
        val runBlock = ChatRenderItem.RunBlock(
            runId = "rshared",
            messages = listOf(a1 to GroupPosition.First, a2 to GroupPosition.Last),
        )
        val userTurn = ChatRenderItem.Single(
            message = UiMessage(id = "u1", role = "user", content = "hi", timestamp = "2026-04-19T12:00:00Z"),
            groupPosition = GroupPosition.None,
        )
        val olderSameRunAssistant = ChatRenderItem.Single(
            message = assistant("a-old", runId = "rshared"),
            groupPosition = GroupPosition.None,
        )
        // RunBlock, then a user turn, then the older same-run assistant — NOT adjacent.
        val out = deduplicateRenderItemsByMessageId(listOf(runBlock, userTurn, olderSameRunAssistant))

        assertEquals(3, out.size)
        assertTrue(out.any { it is ChatRenderItem.Single && (it as ChatRenderItem.Single).message.id == "a-old" })
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
        repeat(2) { buildChatRenderModel(messages, ChatDisplayMode.Interactive) }
        val samples = List(5) {
            val started = TimeSource.Monotonic.markNow()
            buildChatRenderModel(messages, ChatDisplayMode.Interactive)
            started.elapsedNow().inWholeMicroseconds
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
