package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.api.NoActiveRunException
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.ReasoningMessage
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.UserMessage
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeStringUtf8
import io.mockk.mockk
import kotlin.random.Random
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import app.cash.turbine.test
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
/**
 * Integration-level tests for [TimelineSyncLoop] using a programmable fake API.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class TimelineSyncLoopStreamingTest {
    @Test
    fun `streaming assistant text deltas concatenate byte-for-byte under prefix collisions`() = runTest {
        val fragments = listOf(
            "The ",
            "quick brown fox ",
            "jumps over ",
            "the lazy dog ",
            "The quick brown fox jumps over the lazy dog ",     // 44 chars; head EQUALS accumulator → wucn silent-drop
            "again, ",
            "and again — quietly in the moonlight.",            // 38 chars; >=32 AND >=50% → wucn destructive-replace
        )
        val expected = fragments.joinToString("")

        val api = FakeSyncApi()
        // Same serverId on every frame — that's how the merge path is
        // entered. otid only on the first frame so subsequent frames
        // resolve via findByServerId, exercising the production path.
        api.nextStreamMessages = fragments.mapIndexed { idx, f ->
            AssistantMessage(
                id = "reply-stream",
                contentRaw = JsonPrimitive(f),
                otid = if (idx == 0) "reply-otid-uww11" else null,
            )
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

        val send = async { sync.send("hello") }
        advanceUntilIdle()
        send.await()

        val assistant = sync.state.value.events.firstOrNull {
            it is TimelineEvent.Confirmed && it.serverId == "reply-stream"
        }
        assertNotNull("assistant event must be emitted", assistant)
        assertEquals(
            "lettabot-uww.11: streaming deltas must concatenate byte-for-byte under prefix collisions",
            expected,
            assistant!!.content,
        )
        scope.coroutineContext.job.cancel()
    }

    /**
     * Worked example for letta-mobile-7abe: Turbine adoption.
     *
     * Asserts the same pure-delta concat contract the polling-loop sibling
     * test covers above (lcp-cv3 / lcp-pro / lcp-r0m / wucn-snapshot-recovery
     * cascade), but via [app.cash.turbine.test] on the [TimelineSyncLoop.state]
     * StateFlow — no real-time polling, no manual stability heuristic, no
     * arbitrary wall-clock timeout. Each `awaitItem()` is bounded by
     * Turbine's default 1s timeout, so a broken merge surfaces as a clear
     * "expected item but no emission" failure instead of a stable-content
     * heuristic that quietly passes on the wrong content.
     *
     * Read together with `docs/testing-with-turbine.md` for the rationale
     * and the pattern this test demonstrates.
     */
    @Test
    fun `Turbine - pure-delta merge yields concatenated assistant content`() = runTest {
        val fragments = listOf("Hello ", "world", "!")
        val expected = fragments.joinToString("")

        val api = FakeSyncApi()
        api.nextStreamMessages = fragments.mapIndexed { idx, f ->
            AssistantMessage(
                id = "reply-stream",
                contentRaw = JsonPrimitive(f),
                otid = if (idx == 0) "reply-otid-turbine" else null,
            )
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

        sync.state.test {
            // Initial empty timeline before send.
            assertEquals(0, awaitItem().events.size)

            val send = async { sync.send("hello") }
            advanceUntilIdle()
            send.await()

            // Walk emissions until the assistant event reaches the full
            // concatenated content. Conflation may drop intermediate states,
            // but the terminal state must include the expected content.
            var assistantContent: String? = null
            while (assistantContent != expected) {
                val timeline = awaitItem()
                val assistant = timeline.events.firstOrNull {
                    it is TimelineEvent.Confirmed && it.serverId == "reply-stream"
                } as? TimelineEvent.Confirmed
                if (assistant != null) assistantContent = assistant.content
            }
            assertEquals(expected, assistantContent)

            cancelAndIgnoreRemainingEvents()
        }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `duplicate assistant stream seq does not append terminal snapshot`() = runTest {
        val first = "The command results are:\n"
        val second = "- pwd: /opt/stacks/letta-mobile\n- whoami: root\n"
        val full = first + second
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(
                id = "reply-stream",
                contentRaw = JsonPrimitive(first),
                otid = "reply-otid-dup-seq",
                seqId = 1,
            ),
            AssistantMessage(
                id = "reply-stream",
                contentRaw = JsonPrimitive(second),
                seqId = 2,
            ),
            // Replayed/terminal snapshot for the same seq should not be
            // treated as a third delta and appended to itself.
            AssistantMessage(
                id = "reply-stream",
                contentRaw = JsonPrimitive(full),
                seqId = 2,
            ),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

        val send = async { sync.send("run tools") }
        advanceUntilIdle()
        send.await()

        val assistant = sync.state.value.events.firstOrNull {
            it is TimelineEvent.Confirmed && it.serverId == "reply-stream"
        }
        assertNotNull("assistant event must be emitted", assistant)
        assertEquals(full, assistant!!.content)
        assertEquals(
            "terminal snapshot must not be appended as a second copy",
            1,
            assistant.content.windowed(full.length).count { it == full },
        )
        scope.coroutineContext.job.cancel()
    }
    @Test
    fun `submitStreamEvent folds ambient SSE frames through serialized gateway`() = runTest {
        val api = FakeSyncApi()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-gateway", scope)

        sync.submitStreamEvent(
            AssistantMessage(
                id = "gateway-assistant",
                contentRaw = JsonPrimitive("Hello "),
                otid = "gateway-otid",
            )
        )
        sync.submitStreamEvent(
            AssistantMessage(
                id = "gateway-assistant",
                contentRaw = JsonPrimitive("world"),
            )
        )

        assertEquals(
            "submitted frames should not mutate state until the gateway worker runs",
            0,
            sync.state.value.events.size,
        )

        advanceUntilIdle()

        val assistant = sync.state.value.events.single() as TimelineEvent.Confirmed
        assertEquals("gateway-otid", assistant.otid)
        assertEquals("Hello world", assistant.content)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `externalTransportActive does not auto-expire after long idle (letta-mobile-y8tvn)`() = runTest {
        val api = FakeSyncApi()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-no-expiry", scope)

        // Establish WS as the canonical transport: one WS ingest sets the
        // externalTransportActive flag for this conversation.
        sync.ingestStreamEvent(
            AssistantMessage(
                id = "ws-first",
                contentRaw = JsonPrimitive("delivered via WS"),
                otid = "otid-ws-first",
            )
        )
        advanceUntilIdle()
        assertEquals(1, sync.state.value.events.size)

        // Simulate a long idle window — much longer than the previously-
        // hardcoded 120s auto-expiry. The flag must stay set because no
        // explicit clearExternalTransportActive() has been called.
        advanceTimeBy(kotlin.time.Duration.parse("200s"))

        // SSE delivers what would be a duplicate of the WS-ingested message
        // (different id but same logical content — exactly the dual-ingest
        // class we suppress). The flag should still be active, so SSE is
        // suppressed and no second event is appended.
        sync.submitStreamEvent(
            AssistantMessage(
                id = "sse-dup-attempt",
                contentRaw = JsonPrimitive("delivered via WS"),
                otid = "otid-sse-dup",
            )
        )
        advanceUntilIdle()

        assertEquals(
            "SSE frame must be suppressed even after >120s idle — flag is structurally owned, not timer-based",
            1,
            sync.state.value.events.size,
        )

        // Explicit clear restores SSE ingestion.
        sync.clearExternalTransportActive()
        sync.submitStreamEvent(
            AssistantMessage(
                id = "sse-after-clear",
                contentRaw = JsonPrimitive("delivered via SSE"),
                otid = "otid-sse-clear",
            )
        )
        advanceUntilIdle()

        assertEquals(2, sync.state.value.events.size)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `ingested stream frame persists conversation cursor`() = runTest {
        val api = FakeSyncApi()
        val cursorStore = RecordingConversationCursorStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(
            messageApi = MessageApiTimelineTransport(api),
            conversationId = "conv-cursor",
            scope = scope,
            conversationCursorStore = cursorStore,
        )

        sync.ingestStreamEvent(
            AssistantMessage(
                id = "assistant-cursor",
                contentRaw = JsonPrimitive("cursor-bearing frame"),
                seqId = 17,
            )
        )

        assertEquals(listOf("conv-cursor" to 17L), cursorStore.records)
        assertEquals(17L, cursorStore.getCursor("conv-cursor"))
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `cursor repair hydrate records hydrate-end cursor without duplicating events`() = runTest {
        val api = FakeSyncApi()
        api.addStoredMessage(
            AssistantMessage(
                id = "assistant-repair-1",
                contentRaw = JsonPrimitive("first repaired frame"),
                seqId = 11,
            )
        )
        api.addStoredMessage(
            AssistantMessage(
                id = "assistant-repair-2",
                contentRaw = JsonPrimitive("second repaired frame"),
                seqId = 12,
            )
        )
        val cursorStore = RecordingConversationCursorStore()
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(
            messageApi = MessageApiTimelineTransport(api),
            conversationId = "conv-cursor-repair",
            scope = scope,
            conversationCursorStore = cursorStore,
        )

        sync.hydrate(recordConversationCursor = true, fallbackCursorSeq = 10L)
        sync.hydrate(recordConversationCursor = true, fallbackCursorSeq = 12L)

        assertEquals(12L, cursorStore.getCursor("conv-cursor-repair"))
        assertEquals(
            listOf("assistant-repair-1", "assistant-repair-2"),
            sync.state.value.events.map { (it as TimelineEvent.Confirmed).serverId },
        )
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `external transport local sent and failed markers fold through serialized gateway`() = runTest {
        val api = FakeSyncApi()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-external-gateway", scope)

        val appendJob = launch {
            sync.appendExternalTransportLocal(
                content = "from ws",
                otid = "external-otid",
            )
        }

        assertEquals(0, sync.state.value.events.size)

        advanceUntilIdle()
        appendJob.join()

        val appended = sync.state.value.findByOtid("external-otid") as TimelineEvent.Local
        assertEquals(DeliveryState.SENDING, appended.deliveryState)

        val sentJob = launch { sync.markExternalTransportLocalSent("external-otid") }
        advanceUntilIdle()
        sentJob.join()
        val sent = sync.state.value.findByOtid("external-otid") as TimelineEvent.Local
        assertEquals(DeliveryState.SENT, sent.deliveryState)

        val failedJob = launch { sync.markExternalTransportLocalFailed("external-otid") }
        advanceUntilIdle()
        failedJob.join()
        val failed = sync.state.value.findByOtid("external-otid") as TimelineEvent.Local
        assertEquals(DeliveryState.FAILED, failed.deliveryState)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `retry failed local send re-enters serialized gateway before send queue`() = runTest {
        val api = FakeSyncApi()
        api.nextSendFailure = java.io.IOException("first send fails")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-retry-gateway", scope)

        val otid = sync.send("retry me")
        advanceUntilIdle()

        val failed = sync.state.value.findByOtid(otid) as TimelineEvent.Local
        assertEquals(DeliveryState.FAILED, failed.deliveryState)

        val retryJob = launch { sync.retry(otid) }
        assertEquals(
            "retry should not flip state until the gateway worker drains",
            DeliveryState.FAILED,
            (sync.state.value.findByOtid(otid) as TimelineEvent.Local).deliveryState,
        )

        advanceUntilIdle()
        retryJob.join()

        val retried = sync.state.value.findByOtid(otid)
        assertTrue(retried is TimelineEvent.Confirmed)
        assertEquals(2, api.sendCalls)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `streamed reasoning and assistant frames with same server id stay separate`() = runTest {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            ReasoningMessage(
                id = "shared-step-id",
                reasoning = "thinking about it",
            ),
            AssistantMessage(
                id = "shared-step-id",
                contentRaw = JsonPrimitive("final answer"),
            ),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

        val send = async { sync.send("hello") }
        advanceUntilIdle()
        send.await()

        val confirmed = sync.state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
            .filter { it.serverId == "shared-step-id" }
        val reasoning = confirmed.single { it.messageType == TimelineMessageType.REASONING }
        val assistant = confirmed.single { it.messageType == TimelineMessageType.ASSISTANT }
        assertEquals("thinking about it", reasoning.content)
        assertEquals("final answer", assistant.content)
        assertFalse(
            "Reasoning and assistant content must not be concatenated into one echoed bubble",
            assistant.content.contains(reasoning.content) || reasoning.content.contains(assistant.content),
        )
        scope.coroutineContext.job.cancel()
    }

    /**
     * lettabot-uww.11 regression: the literal mermaid block from the
     * 2026-04-26 field repro, streamed character-by-character (the
     * worst-case adversarial chunking exercised on the server side
     * by ws-gateway.e2e.test.ts). Asserts byte-perfect reassembly so
     * we'd catch a regression of the original screenshot.
     */
    @Test
    fun `streaming mermaid block character-by-character reassembles byte-perfectly`() = runTest {
        val mermaid = "A[LLM snapshots] --> B[Coalesce?]"
        val api = FakeSyncApi()
        api.nextStreamMessages = mermaid.mapIndexed { idx, ch ->
            AssistantMessage(
                id = "reply-mermaid",
                contentRaw = JsonPrimitive(ch.toString()),
                otid = if (idx == 0) "mermaid-otid" else null,
            )
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

        val send = async { sync.send("draw it") }
        send.await()

        // Deterministically settle the ingest pipeline before reading state.
        // Reading state.value once right after a single advanceUntilIdle() raced
        // the char-by-char ingest and made this test flaky (letta-mobile-95gij):
        // some frames were still queued on the (virtual) dispatcher. Loop
        // advanceUntilIdle() until the reassembled content stops growing, bounded
        // so a genuine character drop still fails via the assertion below rather
        // than hanging.
        fun currentAssistant(): TimelineEvent? = sync.state.value.events.firstOrNull {
            it is TimelineEvent.Confirmed && it.serverId == "reply-mermaid"
        }
        var lastLen = -1
        repeat(50) {
            advanceUntilIdle()
            val len = currentAssistant()?.content?.length ?: 0
            if (len == mermaid.length || len == lastLen) return@repeat
            lastLen = len
        }
        val assistant = currentAssistant()
        assertNotNull(assistant)
        assertEquals(
            "lettabot-uww.11: mermaid char-by-char reassembly must be byte-perfect",
            mermaid,
            assistant!!.content,
        )
        // Guard against the specific corruption signature from the
        // 2026-04-26 field repro. If either of these substrings is
        // missing, the bubble looked like `A[LLMapshots|`.
        assertTrue(
            "missing 'A[LLM snapshots]' (silent character drop signature)",
            assistant.content.contains("A[LLM snapshots]"),
        )
        assertTrue(
            "missing closing bracket before --> (destructive-replace signature)",
            assistant.content.contains("] --> B[Coalesce?]"),
        )
        scope.coroutineContext.job.cancel()
    }

}
