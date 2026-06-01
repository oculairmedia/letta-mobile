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
import app.cash.turbine.test
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

/**
 * Integration-level tests for [TimelineSyncLoop] using a programmable fake API.
 *
 * The fake produces real SSE byte channels (so the existing [com.letta.mobile.data.stream.SseParser]
 * exercise path is covered) and an in-memory message store to simulate
 * reconciliation via listMessages.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Tag("integration")
class TimelineSyncLoopTest {

    @Test
    fun `hydrate loads initial messages`() = runBlocking {
        val api = FakeSyncApi()
        api.addStoredMessage(SystemMessage(id = "m1", contentRaw = JsonPrimitive("welcome")))
        api.addStoredMessage(AssistantMessage(id = "m2", contentRaw = JsonPrimitive("hi")))

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        sync.hydrate()

        val timeline = sync.state.value
        assertEquals(2, timeline.events.size)
        assertTrue(timeline.events.all { it is TimelineEvent.Confirmed })
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `hydrate skips client-side default shim placeholder conversations`() = runBlocking {
        val api = FakeSyncApi()
        api.addStoredMessage(SystemMessage(id = "m1", contentRaw = JsonPrimitive("welcome")))

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv-default-agent-1", scope)

        sync.hydrate()

        assertEquals(0, api.listMessagesCalls)
        assertEquals(0, sync.state.value.events.size)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `hydrate overfetches raw events so older visible turns survive tool-heavy latest run`() = runBlocking {
        val api = FakeSyncApi()
        api.addStoredMessage(
            UserMessage(
                id = "older-user",
                contentRaw = JsonPrimitive("older prompt"),
                date = "2026-05-07T09:00:00Z",
            )
        )
        api.addStoredMessage(
            AssistantMessage(
                id = "older-assistant",
                contentRaw = JsonPrimitive("older answer"),
                date = "2026-05-07T09:00:01Z",
            )
        )
        repeat(60) { index ->
            api.addStoredMessage(
                ToolCallMessage(
                    id = "tool-$index",
                    date = "2026-05-07T10:00:${index.toString().padStart(2, '0')}Z",
                    runId = "latest-run",
                )
            )
        }
        api.addStoredMessage(
            UserMessage(
                id = "latest-user",
                contentRaw = JsonPrimitive("latest prompt"),
                date = "2026-05-07T10:01:00Z",
            )
        )
        api.addStoredMessage(
            AssistantMessage(
                id = "latest-assistant",
                contentRaw = JsonPrimitive("latest answer"),
                date = "2026-05-07T10:01:01Z",
            )
        )

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        sync.hydrate(limit = 50)

        val ids = sync.state.value.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .map { it.serverId }
        assertTrue("hydrate should request more than the visible target", api.lastConversationLimit!! > 50)
        assertTrue("older visible user message should survive raw overfetch", "older-user" in ids)
        assertTrue("older visible assistant message should survive raw overfetch", "older-assistant" in ids)
        assertTrue("latest turn should still be present", "latest-user" in ids && "latest-assistant" in ids)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `hydrate orders reasoning before final assistant response`() = runBlocking {
        val api = FakeSyncApi()
        val runId = "run-order"
        api.addStoredMessage(
            UserMessage(
                id = "user-1",
                contentRaw = JsonPrimitive("prompt"),
                date = "2026-05-07T10:00:00Z",
            )
        )
        // Simulate REST history returning/persisting the final assistant before its reasoning
        // sibling. Hydrate must still render prompt → thinking → answer.
        api.addStoredMessage(
            AssistantMessage(
                id = "assistant-1",
                contentRaw = JsonPrimitive("final answer"),
                date = "2026-05-07T10:00:02Z",
                runId = runId,
            )
        )
        api.addStoredMessage(
            ReasoningMessage(
                id = "reasoning-1",
                reasoning = "thinking",
                date = "2026-05-07T10:00:01Z",
                runId = runId,
            )
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        sync.hydrate()

        val events = sync.state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(listOf("user-1", "reasoning-1", "assistant-1"), events.map { it.serverId })
        assertEquals(TimelineMessageType.REASONING, events[1].messageType)
        assertEquals(TimelineMessageType.ASSISTANT, events[2].messageType)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `send optimistically appends local event`() = runTest {
        // Uses StandardTestDispatcher (not Unconfined) so the gateway worker
        // and processSendQueue only resume when we explicitly advance the
        // scheduler. That lets us observe the optimistic Local state after the
        // gateway append but before stream+reconcile can swap it to Confirmed.
        val api = FakeSyncApi()
        api.sendResponseGate = CompletableDeferred()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val otidDeferred = async { sync.send("hello") }

        assertEquals(
            "send should submit the Local append to the gateway instead of mutating inline",
            0,
            sync.state.value.events.size,
        )

        testScheduler.runCurrent()

        val otid = otidDeferred.await()
        val local = sync.state.value.findByOtid(otid)

        assertNotNull(local)
        assertTrue(
            "Expected optimistic Local event but found ${local!!::class.simpleName}",
            local is TimelineEvent.Local,
        )
        assertEquals("hello", local.content)
        scope.coroutineContext.job.cancel()
    }
    @Test
    fun `send then stream appends confirmed assistant event`() = runTest {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        sync.state.test {
            assertEquals(0, awaitItem().events.size)

            val userOtidDeferred = async { sync.send("hello") }
            testScheduler.advanceUntilIdle()
            val userOtid = userOtidDeferred.await()

            var timeline = awaitItem()
            while (timeline.findByOtid("reply-otid") == null) {
                timeline = awaitItem()
            }

            val events = timeline.events
            assertEquals(2, events.size)
            assertTrue(events[0].otid == userOtid || events[0].otid.contains(userOtid))
            assertEquals("OK", events[1].content)
            cancelAndIgnoreRemainingEvents()
        }
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `send with image attachments posts Letta content parts`() = runTest {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-image", contentRaw = JsonPrimitive("I see it"), otid = "reply-otid")
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv-image", scope)

        val send = async {
            sync.send(
                content = "describe this image",
                attachments = listOf(MessageContentPart.Image(base64 = "AAAA", mediaType = "image/png")),
            )
        }
        advanceUntilIdle()
        send.await()

        val firstMessage = api.lastSendRequest!!.messages!!.single().jsonObject
        val contentParts = firstMessage["content"]!!.jsonArray
        assertEquals("text", contentParts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("describe this image", contentParts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image", contentParts[1].jsonObject["type"]!!.jsonPrimitive.content)
        val source = contentParts[1].jsonObject["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/png", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("AAAA", source["data"]!!.jsonPrimitive.content)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `send without attachments keeps legacy string content`() = runTest {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-text", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv-text", scope)

        val send = async { sync.send("plain text") }
        advanceUntilIdle()
        send.await()

        val firstMessage = api.lastSendRequest!!.messages!!.single().jsonObject
        assertEquals(JsonPrimitive("plain text"), firstMessage["content"])
        scope.coroutineContext.job.cancel()
    }

    /**
     * lettabot-uww.11 regression: streaming assistant text deltas that
     * share a serverId must concatenate byte-for-byte even when the
     * delta head matches a prefix of the running accumulator. The
     * pre-fix wucn-snapshot-recovery cascade silently dropped such
     * deltas (`oldText.startsWith(newText) -> oldText`) and produced
     * the 2026-04-26 mermaid field repro `A[LLM snapshots]` →
     * `A[LLMapshots|`.
     *
     * This test drives the timeline-path SSE merge — the dominant
     * render path post-conversation-creation — with a delta sequence
     * engineered to hit every defective branch of the old cascade:
     *   - delta head equals accumulator (silent-drop branch)
     *   - delta is >=32 chars AND >= half the accumulator
     *     (destructive-replace branch)
     */
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
        val sync = TimelineSyncLoop(api, "conv1", scope)

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
        val sync = TimelineSyncLoop(api, "conv1", scope)

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
        val sync = TimelineSyncLoop(api, "conv1", scope)

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
        val sync = TimelineSyncLoop(api, "conv-gateway", scope)

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
        val sync = TimelineSyncLoop(api, "conv-no-expiry", scope)

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
            messageApi = api,
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
            messageApi = api,
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
        val sync = TimelineSyncLoop(api, "conv-external-gateway", scope)

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
        val sync = TimelineSyncLoop(api, "conv-retry-gateway", scope)

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
        val sync = TimelineSyncLoop(api, "conv1", scope)

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
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val send = async { sync.send("draw it") }
        advanceUntilIdle()
        send.await()

        val assistant = sync.state.value.events.firstOrNull {
            it is TimelineEvent.Confirmed && it.serverId == "reply-mermaid"
        }
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

    @Test
    fun `reconcile swaps local user event to confirmed`() = runTest {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val send = async { sync.send("hello") }
        advanceUntilIdle()
        val userOtid = send.await()

        // When stream completes, the fake copies sent message + nextStreamMessages into store
        // with our client otid preserved — reconcile should then find it and swap
        val swapped = sync.state.value.findByOtid(userOtid)
        assertTrue(swapped is TimelineEvent.Confirmed)
        assertEquals("hello", swapped!!.content)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `reconcile inserts missed server messages by date instead of appending`() = runBlocking {
        val state = MutableStateFlow(
            Timeline("conv-order")
                .append(
                    TimelineEvent.Confirmed(
                        position = 1.0,
                        otid = "older",
                        content = "older",
                        serverId = "older-server",
                        messageType = TimelineMessageType.ASSISTANT,
                        date = Instant.parse("2026-05-19T06:00:00Z"),
                        runId = null,
                        stepId = null,
                    )
                )
                .append(
                    TimelineEvent.Confirmed(
                        position = 2.0,
                        otid = "newer",
                        content = "newer",
                        serverId = "newer-server",
                        messageType = TimelineMessageType.ASSISTANT,
                        date = Instant.parse("2026-05-19T06:20:00Z"),
                        runId = null,
                        stepId = null,
                    )
                )
        )

        applyReconcileAfterSendSnapshot(
            otid = "unmatched-local",
            conversationId = "conv-order",
            serverMessages = listOf(
                AssistantMessage(
                    id = "missed-server",
                    contentRaw = JsonPrimitive("missed"),
                    otid = "missed",
                    date = "2026-05-19T06:10:00Z",
                )
            ),
            writeMutex = Mutex(),
            state = state,
        )

        val confirmed = state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(listOf("older-server", "missed-server", "newer-server"), confirmed.map { it.serverId })
        assertTrue(confirmed[1].position > confirmed[0].position)
        assertTrue(confirmed[1].position < confirmed[2].position)
    }

    @Test
    fun `reconcile preserves multimodal attachments on confirmed user event`() = runTest {
        val api = FakeSyncApi()
        api.nextStreamMessages = emptyList()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val image = MessageContentPart.Image(base64 = "AAAA", mediaType = "image/jpeg")
        val send = async { sync.send("caption", attachments = listOf(image)) }
        advanceUntilIdle()
        val userOtid = send.await()

        val swapped = sync.state.value.findByOtid(userOtid) as? TimelineEvent.Confirmed
        assertNotNull(swapped)
        assertEquals("caption", swapped!!.content)
        assertEquals(1, swapped.attachments.size)
        assertEquals("image/jpeg", swapped.attachments.first().mediaType)
        assertEquals("AAAA", swapped.attachments.first().base64)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `identical content sent twice produces two local events`() = runTest {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "r1")
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val send1 = async { sync.send("same") }
        val send2 = async { sync.send("same") }
        advanceUntilIdle()
        val otid1 = send1.await()
        val otid2 = send2.await()

        assertTrue(otid1 != otid2)
        val e1 = sync.state.value.findByOtid(otid1)
        val e2 = sync.state.value.findByOtid(otid2)
        assertNotNull(e1)
        assertNotNull(e2)
        assertEquals("same", e1!!.content)
        assertEquals("same", e2!!.content)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `concurrent sends do not collide on position or otid`() = runTest {
        val api = FakeSyncApi()
        api.nextStreamMessages = emptyList()   // no stream body to simplify
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        // Fire 10 concurrent sends
        val sends = (1..10).map { i ->
            async { sync.send("msg $i") }
        }
        advanceUntilIdle()
        sends.forEach { it.await() }

        val timeline = sync.state.value
        assertEquals(10, timeline.events.size)
        // Positions must be strictly increasing (invariant enforced by Timeline.init)
        val positions = timeline.events.map { it.position }
        assertEquals(positions.sorted(), positions)
        // otids must be unique
        assertEquals(10, timeline.events.map { it.otid }.toSet().size)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `reconcile recovers when transient listMessages failure is followed by success`() = runTest {
        // letta-mobile-j44j: reconcileAfterSend retries transient failures
        // (IOException, 5xx) with exponential backoff before giving up.
        // With 2 injected failures and 3 total attempts configured, the
        // third call succeeds and the user bubble still gets swapped to
        // Confirmed — no user-visible error.
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        api.listMessagesFailuresBeforeSuccess = 2  // two 503s, then succeed
        api.listMessagesFailure = ApiException(503, "Service Unavailable")

        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val collectedErrors = mutableListOf<TimelineSyncEvent.ReconcileError>()
        val errorCollector = scope.launch {
            sync.events.collect { ev ->
                if (ev is TimelineSyncEvent.ReconcileError) collectedErrors += ev
            }
        }

        val send = async { sync.send("hello") }
        advanceUntilIdle()
        val userOtid = send.await()

        // Confirmed swap only happens after retry-then-success — if retry
        // were missing, the first 503 would fall straight through to the
        // error branch and the Local would never become Confirmed.
        assertTrue(sync.state.value.findByOtid(userOtid) is TimelineEvent.Confirmed)

        assertEquals(
            "listConversationMessages should have been called 3x (2 failures + 1 success)",
            3,
            api.listMessagesCalls,
        )
        assertTrue(
            "No ReconcileError should be emitted when retry succeeds (got: $collectedErrors)",
            collectedErrors.isEmpty(),
        )

        errorCollector.cancel()
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `reconcile emits ReconcileError when retries are exhausted`() = runTest {
        // letta-mobile-j44j: if listMessages keeps failing past the retry
        // budget, the loop surfaces a ReconcileError event so the UI can
        // show an error + clear the typing indicator. The streamed assistant
        // reply is already on the timeline (the stream itself succeeded);
        // only the post-stream swap of Local→Confirmed is lost.
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        // Fail every listMessages call — the retry budget (3 attempts) will be exhausted
        api.listMessagesFailuresBeforeSuccess = Int.MAX_VALUE
        api.listMessagesFailure = java.io.IOException("simulated network blip")

        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val collectedErrors = mutableListOf<TimelineSyncEvent.ReconcileError>()
        val errorCollector = scope.launch {
            sync.events.collect { ev ->
                if (ev is TimelineSyncEvent.ReconcileError) collectedErrors += ev
            }
        }

        val send = async { sync.send("hello") }
        advanceUntilIdle()
        send.await()

        assertEquals(
            "Should attempt exactly 3 times before giving up",
            3,
            api.listMessagesCalls,
        )
        assertEquals(1, collectedErrors.size)
        assertTrue(
            "Error message should include the underlying cause (got: ${collectedErrors.first().message})",
            collectedErrors.first().message.contains("simulated network blip"),
        )
        // Stream-phase assistant reply still landed — reconcile failure
        // shouldn't nuke the confirmed assistant event that the SSE already
        // appended.
        assertNotNull(
            "Streamed assistant event should still be present after reconcile failure",
            sync.state.value.findByOtid("reply-otid"),
        )

        errorCollector.cancel()
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `recent reconcile on reopen appends server user messages without otid`() = runBlocking {
        val api = FakeSyncApi()
        api.addStoredMessage(
            UserMessage(
                id = "older-user",
                contentRaw = JsonPrimitive("already hydrated"),
                otid = "known-otid",
                date = "2026-05-19T06:00:00Z",
            )
        )

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv-reopen", scope)
        sync.hydrate()

        api.addStoredMessage(
            UserMessage(
                id = "fresh-user-no-otid",
                contentRaw = JsonPrimitive("fresh server prompt"),
                date = "2026-05-19T06:25:00Z",
            )
        )

        sync.reconcileRecentMessages("open")

        val fresh = sync.state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
            .firstOrNull { it.serverId == "fresh-user-no-otid" }
        assertNotNull("reopen reconcile must append fresh server user prompts that have no otid", fresh)
        assertEquals(TimelineMessageType.USER, fresh!!.messageType)
        assertEquals("fresh server prompt", fresh.content)
        assertEquals(
            "hydrate and reopen reconcile should use the same recent-message fetch window",
            listOf(250, 250),
            api.conversationLimits,
        )
        assertEquals(listOf("desc", "desc"), api.conversationOrders)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `recent reconcile appends fresh server message through gateway`() = runTest {
        val api = FakeSyncApi()
        api.addStoredMessage(
            UserMessage(
                id = "older-user",
                contentRaw = JsonPrimitive("already hydrated"),
                otid = "known-otid",
                date = "2026-05-19T06:00:00Z",
            )
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv-reconcile-gateway", scope)

        sync.hydrate()
        api.addStoredMessage(
            UserMessage(
                id = "fresh-user-no-otid",
                contentRaw = JsonPrimitive("fresh server prompt"),
                date = "2026-05-19T06:25:00Z",
            )
        )

        val reconcile = async { sync.reconcileRecentMessages("open") }

        assertEquals(
            "reconcile should enqueue the snapshot before the gateway worker mutates state",
            listOf("older-user"),
            sync.state.value.events.filterIsInstance<TimelineEvent.Confirmed>().map { it.serverId },
        )

        advanceUntilIdle()
        reconcile.await()

        val confirmed = sync.state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(listOf("older-user", "fresh-user-no-otid"), confirmed.map { it.serverId })
        assertEquals("fresh server prompt", confirmed.last().content)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `recent reconcile skips REST snapshot while stream subscriber is active`() = runTest {
        val api = FakeSyncApi()
        api.streamConversationReturnsOpenChannel = true
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv-live-skip", scope)

        runCurrent()
        assertTrue("stream subscriber should be active before reconcile", sync.streamSubscriberActive.value)

        api.addStoredMessage(
            UserMessage(
                id = "fresh-user-no-otid",
                contentRaw = JsonPrimitive("fresh server prompt"),
                date = "2026-05-19T06:25:00Z",
            )
        )

        sync.reconcileRecentMessages("open")
        runCurrent()

        assertEquals("active stream should remain the only live writer", 0, api.listMessagesCalls)
        assertTrue(sync.state.value.events.isEmpty())
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `forced recent reconcile fetches exactly once while stream subscriber is active`() = runTest {
        val api = FakeSyncApi()
        api.streamConversationReturnsOpenChannel = true
        api.addStoredMessage(
            UserMessage(
                id = "fresh-user-no-otid",
                contentRaw = JsonPrimitive("fresh server prompt"),
                date = "2026-05-19T06:25:00Z",
            )
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv-live-refresh", scope)

        runCurrent()
        assertTrue("stream subscriber should be active before forced reconcile", sync.streamSubscriberActive.value)

        val reconcile = async { sync.reconcileRecentMessages("pull-to-refresh", forceRefresh = true) }
        runCurrent()
        reconcile.await()

        assertEquals("pull-to-refresh should perform one REST snapshot", 1, api.listMessagesCalls)
        val confirmed = sync.state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
        assertEquals(listOf("fresh-user-no-otid"), confirmed.map { it.serverId })
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `reconcile does not retry on 4xx permanent errors`() = runTest {
        // letta-mobile-j44j: 4xx responses (auth, validation) won't become
        // true on retry, so we fail fast to avoid wasting up to 1.4s of
        // backoff sleeping on a permanent error.
        val api = FakeSyncApi()
        api.nextStreamMessages = emptyList()
        api.listMessagesFailuresBeforeSuccess = Int.MAX_VALUE
        api.listMessagesFailure = ApiException(401, "Unauthorized")

        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val collectedErrors = mutableListOf<TimelineSyncEvent.ReconcileError>()
        val errorCollector = scope.launch {
            sync.events.collect { ev ->
                if (ev is TimelineSyncEvent.ReconcileError) collectedErrors += ev
            }
        }

        val send = async { sync.send("hello") }
        advanceUntilIdle()
        send.await()

        assertEquals(
            "Permanent 4xx should fail on the first attempt, no retry",
            1,
            api.listMessagesCalls,
        )
        assertEquals(1, collectedErrors.size)

        errorCollector.cancel()
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `retry on non-FAILED event is a no-op`() = runTest {
        // letta-mobile-lbmy: the fix moves the read of findByOtid inside
        // writeMutex.withLock. The test here is a behavioural check that
        // retry() on a non-existent otid returns without mutating state —
        // enforcing the read-inside-lock pattern would require instrumenting
        // the mutex which is out of scope; we trust the code review for the
        // TOCTOU property itself.
        val api = FakeSyncApi()
        api.nextStreamMessages = emptyList()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        // Retry on an otid that doesn't exist at all — must not throw, must not
        // mutate state.
        val retry = async { sync.retry("client-nonexistent") }
        advanceUntilIdle()
        retry.await()
        assertEquals(0, sync.state.value.events.size)

        scope.coroutineContext.job.cancel()
    }

    // --- letta-mobile-mge5.20: tool call / approval / return wiring --------

    @Test
    fun `hydrate attaches tool_return to its invoking ApprovalRequest`() = runBlocking {
        val api = FakeSyncApi()
        val reqId = "req-1"
        val tcid = "toolu_abc"
        val toolCall = com.letta.mobile.data.model.ToolCall(
            id = tcid, name = "Bash", arguments = "{\"command\":\"echo hi\"}",
        )
        api.addStoredMessage(
            com.letta.mobile.data.model.ApprovalRequestMessage(
                id = reqId, toolCalls = listOf(toolCall),
            )
        )
        api.addStoredMessage(
            com.letta.mobile.data.model.ToolReturnMessage(
                id = "ret-1",
                toolCallId = tcid,
                toolReturnRaw = JsonPrimitive("hi\n"),
            )
        )

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv1", scope)
        sync.hydrate()

        val events = sync.state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
        // The tool_return should NOT appear as a standalone event.
        assertEquals(
            "Expected exactly one bubble (the TOOL_CALL), not a separate TOOL_RETURN",
            1, events.size,
        )
        val bubble = events.single()
        assertEquals(TimelineMessageType.TOOL_CALL, bubble.messageType)
        assertEquals("hi\n", bubble.toolReturnContent)
        assertTrue("approvalDecided must be true once return observed", bubble.approvalDecided)

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `hydrate attaches batched tool returns by tool call id`() = runBlocking {
        val api = FakeSyncApi()
        val callA = com.letta.mobile.data.model.ToolCall(
            id = "toolu_a", name = "Bash", arguments = "{\"command\":\"a\"}",
        )
        val callB = com.letta.mobile.data.model.ToolCall(
            id = "toolu_b", name = "Bash", arguments = "{\"command\":\"b\"}",
        )
        api.addStoredMessage(
            com.letta.mobile.data.model.ApprovalRequestMessage(
                id = "req-batch",
                toolCalls = listOf(callA, callB),
            )
        )
        api.addStoredMessage(
            com.letta.mobile.data.model.ToolReturnMessage(
                id = "ret-a",
                toolCallId = "toolu_a",
                toolReturnRaw = JsonPrimitive("a-output"),
            )
        )
        api.addStoredMessage(
            com.letta.mobile.data.model.ToolReturnMessage(
                id = "ret-b",
                toolCallId = "toolu_b",
                toolReturnRaw = JsonPrimitive("b-output"),
                status = "error",
            )
        )

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv-batch", scope)
        sync.hydrate()

        val bubble = sync.state.value.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .single { it.messageType == TimelineMessageType.TOOL_CALL }
        assertEquals("a-output", bubble.toolReturnContentByCallId["toolu_a"])
        assertEquals("b-output", bubble.toolReturnContentByCallId["toolu_b"])
        assertEquals(false, bubble.toolReturnIsErrorByCallId["toolu_a"])
        assertEquals(true, bubble.toolReturnIsErrorByCallId["toolu_b"])
        assertEquals("a-output", bubble.toolReturnContent)
        assertTrue(bubble.approvalDecided)

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `streamed tool_return attaches to tool_call event and flips approvalDecided`() = runTest {
        val api = FakeSyncApi()
        val reqId = "req-2"
        val tcid = "toolu_xyz"
        val toolCall = com.letta.mobile.data.model.ToolCall(
            id = tcid, name = "Bash", arguments = "{\"command\":\"ls\"}",
        )
        // Seed the store with a user message so send has something to build from.
        api.nextStreamMessages = listOf(
            com.letta.mobile.data.model.ApprovalRequestMessage(
                id = reqId, toolCalls = listOf(toolCall),
            ),
            com.letta.mobile.data.model.ToolReturnMessage(
                id = "ret-2",
                toolCallId = tcid,
                toolReturnRaw = JsonPrimitive("file_a\nfile_b\n"),
            ),
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val send = async { sync.send("list files") }
        advanceUntilIdle()
        send.await()

        val confirmed = sync.state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
        val bubbleByType = confirmed.groupBy { it.messageType }
        assertFalse(
            "Standalone TOOL_RETURN event leaked through into the timeline",
            bubbleByType.containsKey(TimelineMessageType.TOOL_RETURN),
        )
        val toolCallEvent = bubbleByType[TimelineMessageType.TOOL_CALL]?.single()
        assertNotNull(toolCallEvent)
        assertEquals("file_a\nfile_b\n", toolCallEvent!!.toolReturnContent)
        assertTrue(toolCallEvent.approvalDecided)

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `streamed tool_return arriving before tool_call attaches when call lands`() = runBlocking {
        val api = FakeSyncApi()
        val job = kotlinx.coroutines.Job()
        val scope = CoroutineScope(Dispatchers.Unconfined + job)
        val sync = TimelineSyncLoop(api, "conv1", scope)
        val toolCallId = "toolu_early_return"

        try {
            sync.ingestStreamEvent(
                com.letta.mobile.data.model.ToolReturnMessage(
                    id = "return-before-call",
                    toolCallId = toolCallId,
                    toolReturnRaw = JsonPrimitive("early output"),
                    status = "success",
                    runId = "run-early",
                )
            )

            assertTrue(
                "tool_return should not render as a standalone event",
                sync.state.value.events.none {
                    it is TimelineEvent.Confirmed && it.messageType == TimelineMessageType.TOOL_RETURN
                },
            )

            sync.ingestStreamEvent(
                com.letta.mobile.data.model.ApprovalRequestMessage(
                    id = "call-after-return",
                    runId = "run-early",
                    toolCall = com.letta.mobile.data.model.ToolCall(
                        id = toolCallId,
                        name = "Bash",
                        arguments = "{\"command\":\"echo early\"}",
                    ),
                )
            )

            val toolCallEvent = sync.state.value.events
                .filterIsInstance<TimelineEvent.Confirmed>()
                .single { it.messageType == TimelineMessageType.TOOL_CALL }
            assertEquals("early output", toolCallEvent.toolReturnContent)
            assertEquals("early output", toolCallEvent.toolReturnContentByCallId[toolCallId])
            assertTrue(toolCallEvent.approvalDecided)
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `tool_return with short discriminator is still dispatched via SseParser`() = runBlocking {
        // letta-mobile-mge5.18 regression guard: SSE uses message_type="tool_return"
        // (without _message). LettaMessageSerializer must accept both forms.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val short = """{"message_type":"tool_return","id":"r1","tool_call_id":"tc1","tool_return":"done"}"""
        val parsed = json.decodeFromString(
            com.letta.mobile.data.model.LettaMessage.serializer(), short,
        )
        assertTrue(
            "short-form discriminator must decode to ToolReturnMessage; got ${parsed::class.simpleName}",
            parsed is com.letta.mobile.data.model.ToolReturnMessage,
        )
        val long = """{"message_type":"tool_return_message","id":"r2","tool_call_id":"tc2","tool_return":"ok"}"""
        val parsedLong = json.decodeFromString(
            com.letta.mobile.data.model.LettaMessage.serializer(), long,
        )
        assertTrue(parsedLong is com.letta.mobile.data.model.ToolReturnMessage)
    }

    @Test
    fun `approval_response with short discriminator is dispatched via SseParser`() = runBlocking {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val short = """{"message_type":"approval_response","id":"x","approval_request_id":"req-1","approve":true}"""
        val parsed = json.decodeFromString(
            com.letta.mobile.data.model.LettaMessage.serializer(), short,
        )
        assertTrue(
            "short form must decode to ApprovalResponseMessage; got ${parsed::class.simpleName}",
            parsed is com.letta.mobile.data.model.ApprovalResponseMessage,
        )
    }

    @Test
    fun `reasoning short and long discriminators both decode to ReasoningMessage`() = runBlocking {
        // letta-mobile-mge5.22: SSE emits "reasoning"; REST emits
        // "reasoning_message". Both must land in ReasoningMessage or inner
        // thoughts disappear from the timeline.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val short = """{"message_type":"reasoning","id":"r1","reasoning":"thinking..."}"""
        val long = """{"message_type":"reasoning_message","id":"r2","reasoning":"thinking again"}"""
        listOf(short, long).forEach { frame ->
            val parsed = json.decodeFromString(
                com.letta.mobile.data.model.LettaMessage.serializer(), frame,
            )
            assertTrue(
                "Frame $frame decoded to ${parsed::class.simpleName} — expected ReasoningMessage",
                parsed is com.letta.mobile.data.model.ReasoningMessage,
            )
        }
    }

    @Test
    fun `all known discriminator pairs decode as expected`() = runBlocking {
        // Regression guard for mge5.16/18/22 — whenever the server adds a
        // new short discriminator we should catch it here first before the
        // UI silently drops messages.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
        data class Case(val frame: String, val expected: Class<*>)
        val cases = listOf(
            Case("""{"message_type":"user_message","id":"u","content":"hi"}""",
                com.letta.mobile.data.model.UserMessage::class.java),
            Case("""{"message_type":"assistant_message","id":"a","content":"ok"}""",
                com.letta.mobile.data.model.AssistantMessage::class.java),
            Case("""{"message_type":"system_message","id":"s","content":"sys"}""",
                com.letta.mobile.data.model.SystemMessage::class.java),
            Case("""{"message_type":"reasoning","id":"r","reasoning":"t"}""",
                com.letta.mobile.data.model.ReasoningMessage::class.java),
            Case("""{"message_type":"reasoning_message","id":"r","reasoning":"t"}""",
                com.letta.mobile.data.model.ReasoningMessage::class.java),
            Case("""{"message_type":"hidden_reasoning","id":"h","state":"redacted"}""",
                com.letta.mobile.data.model.HiddenReasoningMessage::class.java),
            Case("""{"message_type":"hidden_reasoning_message","id":"h","state":"redacted"}""",
                com.letta.mobile.data.model.HiddenReasoningMessage::class.java),
            Case("""{"message_type":"tool_call","id":"tc"}""",
                com.letta.mobile.data.model.ToolCallMessage::class.java),
            Case("""{"message_type":"tool_call_message","id":"tc"}""",
                com.letta.mobile.data.model.ToolCallMessage::class.java),
            Case("""{"message_type":"tool_return","id":"tr","tool_call_id":"x","tool_return":"r"}""",
                com.letta.mobile.data.model.ToolReturnMessage::class.java),
            Case("""{"message_type":"tool_return_message","id":"tr","tool_call_id":"x","tool_return":"r"}""",
                com.letta.mobile.data.model.ToolReturnMessage::class.java),
            Case("""{"message_type":"approval_request","id":"ar"}""",
                com.letta.mobile.data.model.ApprovalRequestMessage::class.java),
            Case("""{"message_type":"approval_request_message","id":"ar"}""",
                com.letta.mobile.data.model.ApprovalRequestMessage::class.java),
            Case("""{"message_type":"approval_response","id":"ap","approval_request_id":"r","approve":true}""",
                com.letta.mobile.data.model.ApprovalResponseMessage::class.java),
            Case("""{"message_type":"approval_response_message","id":"ap","approval_request_id":"r","approve":true}""",
                com.letta.mobile.data.model.ApprovalResponseMessage::class.java),
            Case("""{"message_type":"stop_reason","stop_reason":"end_turn"}""",
                com.letta.mobile.data.model.StopReason::class.java),
        )
        cases.forEach { (frame, expected) ->
            val parsed = json.decodeFromString(
                com.letta.mobile.data.model.LettaMessage.serializer(), frame,
            )
            assertTrue(
                "Frame $frame decoded to ${parsed::class.simpleName}; expected ${expected.simpleName}",
                expected.isInstance(parsed),
            )
        }
    }

    @Test
    fun `streamed approval_request followed by tool_return preserves output on later delta frame`() = runBlocking {
        // letta-mobile-mge5.23: repro of the "approve/reject still visible
        // + no output" symptom. Sequence of SSE frames observed in practice:
        //   1. approval_request (streaming delta 1) with empty args
        //   2. approval_request (streaming delta 2) with populated args
        //   3. tool_return_message with the output
        //   4. approval_request (final settling delta) with the same
        //      server id but potentially empty fields — if this overwrites
        //      the already-attached toolReturnContent, the UI regresses to
        //      empty-output / approve-reject-visible state.
        // The merge path in ingestStreamEvent must preserve existing
        // toolReturnContent/approvalDecided/toolCalls on such deltas.
        val api = FakeSyncApi()
        val job = kotlinx.coroutines.Job()
        val loop = TimelineSyncLoop(
            conversationId = "conv-x",
            messageApi = api,
            scope = CoroutineScope(Dispatchers.Unconfined + job),
        )
        try {
        loop.hydrate(limit = 50)

        val approvalBase = com.letta.mobile.data.model.ApprovalRequestMessage(
            id = "msg-ar-1",
            runId = "run-1",
            otid = "otid-ar-1",
            toolCall = com.letta.mobile.data.model.ToolCall(
                id = "toolu_xyz", name = "Bash", arguments = "{\"command\":\"echo ok\"}",
            ),
        )
        // Frame 1: populated call
        loop.ingestStreamEvent(approvalBase)
        // Frame 2: tool_return for the call
        val toolReturn = com.letta.mobile.data.model.ToolReturnMessage(
            id = "msg-tr-1",
            toolCallId = "toolu_xyz",
            toolReturnRaw = kotlinx.serialization.json.JsonPrimitive("ok\n"),
            status = "success",
            runId = "run-1",
        )
        loop.ingestStreamEvent(toolReturn)
        // Frame 3: settling delta with the same server id but blank args
        val stale = com.letta.mobile.data.model.ApprovalRequestMessage(
            id = "msg-ar-1",
            runId = "run-1",
            otid = "otid-ar-1",
            toolCall = com.letta.mobile.data.model.ToolCall(
                id = "toolu_xyz", name = "Bash", arguments = null,
            ),
        )
        loop.ingestStreamEvent(stale)

        val final = loop.state.value.events
            .filterIsInstance<TimelineEvent.Confirmed>()
            .firstOrNull { it.serverId == "msg-ar-1" }
        assertNotNull("call event must still exist after settling delta", final)
        assertTrue("approvalDecided must survive the settling delta", final!!.approvalDecided)
        assertEquals("toolReturnContent must survive the settling delta", "ok\n", final.toolReturnContent)
        // args should have been preserved from the populated earlier frame
        val firstCall = final.toolCalls.firstOrNull()
        assertNotNull(firstCall)
        assertEquals("{\"command\":\"echo ok\"}", firstCall!!.arguments)
        } finally {
            job.cancel()
        }
    }

    /**
     * letta-mobile-gqz3 regression test (updated for letta-mobile-t8q7).
     *
     * Original gqz3 bug: the subscriber's `ApiException` catch branch only
     * treated `"No active runs"` as an idle pattern. When a previously-
     * active run aged out, the server returned
     * `EXPIRED: Run was created more than 3 hours ago, and is now expired.`
     * which the subscriber mis-classified as a generic network error,
     * wedging at the backoff cap and never opening a fresh stream.
     *
     * Post-t8q7: idle-pattern classification (including EXPIRED) now lives
     * in `MessageApi.streamConversation` and is signalled by the stackless
     * `NoActiveRunException`. The subscriber's ApiException branch only
     * handles real network/server errors. This contract test verifies
     * EXPIRED still reaches the idle path — it just goes through the
     * stackless exception now.
     *
     * Assertion: observing EXPIRED must emit `streamSubscriber.idle404`
     * and must NOT emit `streamSubscriber.error` or `streamSubscriber.networkError`.
     */
    @Test
    fun `EXPIRED run is classified as idle, not error`() = runTest {
        // Clear the Telemetry ring so we can look at an empty slate. This is
        // necessary because other tests sharing the same JVM worker may have
        // left unrelated events behind.
        com.letta.mobile.util.Telemetry.clear()

        val api = ExpiredThenIdleApi()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)

        // Instantiating TimelineSyncLoop starts the subscriber coroutine in init{}.
        TimelineSyncLoop(api, "conv-gqz3", scope)

        // Let the subscriber attempt one stream call.
        advanceUntilIdle()

        // Telemetry ring is newest-first (addFirst semantics), so we just
        // filter the whole thing by (tag, name) rather than slicing.
        val events = com.letta.mobile.util.Telemetry.snapshot()
        val idle404 = events.firstOrNull {
            it.tag == "TimelineSync" &&
                it.name == "streamSubscriber.idle404" &&
                (it.attrs["conversationId"] as? String) == "conv-gqz3"
        }
        assertNotNull(
            "EXPIRED must be classified as idle (streamSubscriber.idle404). " +
                "Saw events: ${events.map { "${it.tag}/${it.name}" }}",
            idle404,
        )
        // And explicitly: there must not be a streamSubscriber.error / networkError
        // for this cycle — before the fix, the wedge produced one error per retry.
        val errorForGqz3 = events.firstOrNull {
            it.tag == "TimelineSync" &&
                (it.name == "streamSubscriber.error" || it.name == "streamSubscriber.networkError") &&
                (it.attrs["conversationId"] as? String) == "conv-gqz3"
        }
        assertEquals(
            "EXPIRED must NOT produce a streamSubscriber.error/networkError (it's an idle pattern, not a real failure)",
            null,
            errorForGqz3,
        )

scope.coroutineContext.job.cancel()
    }

    /**
     * letta-mobile-qv6d regression test.
     *
     * Before the fix, the streamSubscriber idle backoff capped at
     * STREAM_BACKOFF_MAX_MS (5s). With ChatPushService warming 20
     * conversations on app start that produced ~4 RPS of background
     * 400 traffic against letta.oculair.ca per device — the OkHttp
     * dispatcher saturated and starved foreground SSE sends
     * (Emmanuel's letta-mobile-kxsv hang on 2026-04-25).
     *
     * The fix splits the cap: clean closes still reset to 1s and re-cap
     * at 5s (the STREAM_BACKOFF_MAX_MS field), but the idle path uses a
     * separate STREAM_IDLE_BACKOFF_MAX_MS (90s).
     *
     * This test asserts that after a sustained idle stretch — five
     * back-to-back ApiException(idle) returns — the most recent
     * idle404 telemetry event reports a backoffMs > 5_000 (i.e. the
     * 5s cap is no longer active on the idle ladder).
     */
    @Test
    fun `idle backoff cap is well above the prior 5s cap`() = runTest {
        com.letta.mobile.util.Telemetry.clear()

        val api = AlwaysIdleApi()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)

        val sync = TimelineSyncLoop(api, "conv-qv6d", scope)

        // Drive the subscriber through ≥6 idle iterations on virtual time.
        // Backoff doubles 1_000 → 2_000 → 4_000 → 8_000 → 16_000 → 32_000.
        // After 6 iterations the next-scheduled backoff is well over the
        // prior 5s cap. advanceTimeBy() is virtual, so no wall-clock wait.
        repeat(6) {
            testScheduler.advanceTimeBy(120_000)
            testScheduler.runCurrent()
        }

        assertTrue(
            "expected ≥ 6 stream calls under virtual time, saw ${api.streamCallCount}",
            api.streamCallCount >= 6,
        )

        val events = com.letta.mobile.util.Telemetry.snapshot()
        val backoffs = events
            .filter {
                it.tag == "TimelineSync" &&
                    it.name == "streamSubscriber.idle404" &&
                    (it.attrs["conversationId"] as? String) == "conv-qv6d"
            }
            .mapNotNull { it.attrs["backoffMs"] as? Long }

        assertTrue(
            "expected at least 5 idle404 events for conv-qv6d, saw ${backoffs.size}",
            backoffs.size >= 5,
        )
        // Telemetry ring is newest-first.
        val latest = backoffs.first()
        assertTrue(
            "idle backoff stuck at the prior 5s cap (latest=$latest). " +
                "letta-mobile-qv6d regression — idle ladder must now reach the higher cap.",
            latest > 5_000L,
        )

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `silent open stream triggers watchdog reconnect`() = runTest {
        com.letta.mobile.util.Telemetry.clear()

        val api = SilentAfterHeartbeatApi()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)

        TimelineSyncLoop(
            api,
            "conv-watchdog",
            scope,
            streamSilenceTimeoutMs = 1_000L,
        )

        testScheduler.advanceTimeBy(6_000)
        testScheduler.runCurrent()

        assertTrue(
            "expected watchdog reconnect to open the stream more than once, saw ${api.streamCallCount}",
            api.streamCallCount >= 2,
        )

        val events = com.letta.mobile.util.Telemetry.snapshot()
        assertNotNull(
            "expected streamSubscriber.silenceTimeout telemetry",
            events.firstOrNull {
                it.tag == "TimelineSync" &&
                    it.name == "streamSubscriber.silenceTimeout" &&
                    (it.attrs["conversationId"] as? String) == "conv-watchdog"
            },
        )
        assertNotNull(
            "expected streamSubscriber.watchdogReconnect telemetry",
            events.firstOrNull {
                it.tag == "TimelineSync" &&
                    it.name == "streamSubscriber.watchdogReconnect" &&
                    (it.attrs["conversationId"] as? String) == "conv-watchdog" &&
                    (it.attrs["reason"] as? String) == "silenceTimeout"
            },
        )

        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `stream subscriber dispatches to listener resolved after loop creation`() = runTest {
        com.letta.mobile.util.Telemetry.clear()
        val api = OneShotAssistantStreamApi()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        var listener: IngestedMessageListener? = null
        val received = mutableListOf<String>()

        TimelineSyncLoop(
            messageApi = api,
            conversationId = "conv-dynamic-listener",
            scope = scope,
            ingestedListenerProvider = { listener },
        )

        listener = object : IngestedMessageListener {
            override suspend fun onMessageIngested(
                conversationId: String,
                serverId: String,
                messageType: String?,
                contentPreview: String?,
            ) {
                received += listOf(conversationId, serverId, messageType.orEmpty(), contentPreview.orEmpty())
            }
        }

        repeat(10) {
            if (received.isNotEmpty()) return@repeat
            testScheduler.advanceTimeBy(100)
            testScheduler.runCurrent()
        }

        assertEquals(
            listOf("conv-dynamic-listener", "asst-dynamic", "assistant_message", "late listener works"),
            received,
        )
        val dispatchEvent = com.letta.mobile.util.Telemetry.snapshot().firstOrNull {
            it.tag == "TimelineSync" &&
                it.name == "streamSubscriber.listenerDispatch" &&
                (it.attrs["conversationId"] as? String) == "conv-dynamic-listener"
        }
        assertNotNull("expected listener dispatch telemetry", dispatchEvent)
        assertEquals("asst-dynamic", dispatchEvent!!.attrs["serverId"])
        assertEquals("assistant_message", dispatchEvent.attrs["messageType"])
        assertEquals(true, dispatchEvent.attrs["hasListener"])
        assertEquals(19, dispatchEvent.attrs["previewLength"])
        assertFalse("listener dispatch telemetry must not include raw preview", dispatchEvent.attrs.containsKey("contentPreview"))
        scope.coroutineContext.job.cancel()
    }
}

private class BlockingListApi : MessageApi(mockk(relaxed = true)) {
    val listStarted = CompletableDeferred<Unit>()
    val releaseList = CompletableDeferred<List<LettaMessage>>()

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        kotlinx.coroutines.awaitCancellation()
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        listStarted.complete(Unit)
        return releaseList.await()
    }
}

private class OpenStreamApi : MessageApi(mockk(relaxed = true)) {
    val streamOpened = CompletableDeferred<Unit>()
    @Volatile var listMessagesCalls: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        streamOpened.complete(Unit)
        return ByteChannel(autoFlush = true)
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        listMessagesCalls++
        return emptyList()
    }
}

private class OneShotAssistantStreamApi : MessageApi(mockk(relaxed = true)) {
    private var opened = false

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        if (opened) kotlinx.coroutines.awaitCancellation()
        opened = true
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val message = AssistantMessage(
            id = "asst-dynamic",
            contentRaw = JsonPrimitive("late listener works"),
        )
        val sseBody = buildString {
            append("data: ")
            append(json.encodeToString(LettaMessage.serializer(), message))
            append("\n\n")
            append("data: [DONE]\n\n")
        }
        return ByteReadChannel(sseBody.toByteArray())
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = emptyList()
}

private class SilentAfterHeartbeatApi : MessageApi(mockk(relaxed = true)) {
    @Volatile var streamCallCount: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        streamCallCount++
        val channel = ByteChannel(autoFlush = true)
        channel.writeStringUtf8(": ping\n\n")
        return channel
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = emptyList()
}

private class AlwaysIdleApi : MessageApi(mockk(relaxed = true)) {
    @Volatile var streamCallCount: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        streamCallCount++
        // letta-mobile-t8q7: real MessageApi classifies the "No active runs"
        // 400 body into NoActiveRunException before it reaches the subscriber.
        throw NoActiveRunException(conversationId.value)
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = emptyList()
}

/**
 * Fake api for the gqz3 regression test. The real `MessageApi.streamConversation`
 * classifies EXPIRED bodies into `NoActiveRunException` (letta-mobile-t8q7) so
 * this fake mirrors that contract: first call throws `NoActiveRunException`,
 * subsequent calls idle. Before t8q7 the fake threw `ApiException` and the
 * subscriber re-classified by message-text in its catch block.
 */
private class ExpiredThenIdleApi : MessageApi(mockk(relaxed = true)) {
    @Volatile var streamCallCount: Int = 0

    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        streamCallCount++
        if (streamCallCount == 1) {
            throw NoActiveRunException(conversationId.value)
        }
        kotlinx.coroutines.awaitCancellation()
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = emptyList()
}

/**
 * Fake [MessageApi] that simulates:
 * - a stored message list (returned by listMessages)
 * - a programmable stream (returned by sendConversationMessage as a real SSE byte channel)
 *
 * On each send: the user message is added to the store with its otid preserved,
 * and the stream yields [nextStreamMessages] as SSE events.
 */
private class FakeSyncApi : MessageApi(mockk(relaxed = true)) {
    private val stored = mutableListOf<LettaMessage>()
    var nextStreamMessages: List<LettaMessage> = emptyList()
    var lastSendRequest: MessageCreateRequest? = null
    var sendResponseGate: CompletableDeferred<Unit>? = null
    var nextSendFailure: Throwable? = null
    var sendCalls: Int = 0

    // letta-mobile-j44j: failure-injection for reconcile retry tests.
    // When [listMessagesFailuresBeforeSuccess] > 0, the first N calls to
    // [listConversationMessages] throw [listMessagesFailure] (or a default
    // IOException if none is set). Subsequent calls return normally.
    var listMessagesFailuresBeforeSuccess: Int = 0
    var listMessagesFailure: Throwable? = null
    var listMessagesCalls: Int = 0
    var lastConversationLimit: Int? = null
    val conversationLimits = mutableListOf<Int?>()
    val conversationOrders = mutableListOf<String?>()
    var streamConversationReturnsOpenChannel: Boolean = false

    fun addStoredMessage(msg: LettaMessage) {
        stored.add(msg)
    }

    // The default `MessageApi.streamConversation` calls into a relaxed-mockk
    // HttpClient and returns an ApiException on every invocation, which sends
    // `runStreamSubscriber` into a 5s-delay retry loop that accumulates
    // timers for the full duration of each test (observed: 91s for one test
    // that never exercises the subscriber). Idle here instead so the loop
    // suspends until the test's scope is cancelled. letta-mobile-o8pr.
    override suspend fun streamConversation(conversationId: ConversationId): ByteReadChannel {
        if (streamConversationReturnsOpenChannel) {
            return ByteChannel()
        }
        kotlinx.coroutines.awaitCancellation()
    }

    override suspend fun listConversationMessages(
        conversationId: ConversationId,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        listMessagesCalls++
        lastConversationLimit = limit
        conversationLimits += limit
        conversationOrders += order
        if (listMessagesFailuresBeforeSuccess > 0) {
            listMessagesFailuresBeforeSuccess--
            throw listMessagesFailure
                ?: java.io.IOException("injected listConversationMessages failure")
        }
        val ordered = if (order == "desc") stored.reversed() else stored.toList()
        return if (limit != null) ordered.take(limit) else ordered
    }

    override suspend fun sendConversationMessage(
        conversationId: ConversationId,
        request: MessageCreateRequest,
    ): ByteReadChannel {
        sendCalls++
        nextSendFailure?.let { failure ->
            nextSendFailure = null
            throw failure
        }
        lastSendRequest = request
        sendResponseGate?.await()
        // Extract otid from request and create a UserMessage in the store to
        // mimic server persistence.
        val firstMessage = request.messages?.firstOrNull()
        val otid = firstMessage?.let {
            (it as? kotlinx.serialization.json.JsonObject)?.get("otid")?.let { v ->
                (v as? JsonPrimitive)?.contentOrNull
            }
        }
        val userContent = firstMessage?.let {
            (it as? JsonObject)?.get("content")
        }
        if (otid != null) {
            stored.add(
                UserMessage(
                    id = "message-$otid",
                    contentRaw = userContent ?: JsonPrimitive(""),
                    otid = otid,
                )
            )
        }

        // Emit nextStreamMessages as real SSE frames and close
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val sseBody = buildString {
            nextStreamMessages.forEach { msg ->
                append("data: ")
                append(json.encodeToString(LettaMessage.serializer(), msg))
                append("\n\n")
            }
            append("data: [DONE]\n\n")
        }

        // Also add stream messages to the store so subsequent listMessages reflects them
        stored.addAll(nextStreamMessages)

        // Pre-filled ByteReadChannel — no background writer coroutine. The
        // previous GlobalScope.launch pattern leaked coroutines across tests
        // in the same JVM worker, eventually OOMing CI (letta-mobile-o8pr).
        return ByteReadChannel(sseBody.toByteArray())
    }
}

private fun <T> List<T>.randomOrNull(random: Random): T? =
    if (isEmpty()) null else this[random.nextInt(size)]

private class RecordingConversationCursorStore : ConversationCursorStore {
    val records = mutableListOf<Pair<String, Long>>()
    private val highestByConversation = mutableMapOf<String, Long>()

    override suspend fun recordFrame(conversationId: String, seq: Long) {
        records += conversationId to seq
        highestByConversation[conversationId] = maxOf(highestByConversation[conversationId] ?: Long.MIN_VALUE, seq)
    }

    override suspend fun getCursor(conversationId: String): Long? =
        highestByConversation[conversationId]?.takeIf { it != Long.MIN_VALUE }

    override suspend fun getAllCursors(): Map<String, Long> =
        highestByConversation.filterValues { it != Long.MIN_VALUE }

    override suspend fun clearCursor(conversationId: String) {
        highestByConversation.remove(conversationId)
    }
}

private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
    get() = if (isString) content else content.takeIf { it != "null" }

/**
 * mge5.24: image attachments must survive a process restart even when the
 * server (correctly observed in production) drops user_message records that
 * carry non-text content.
 */
class TimelineSyncLoopImageRestoreTest {

    private class FakeStore : PendingLocalStore {
        val rows = mutableMapOf<String, PendingLocalRecord>()
        override suspend fun save(record: PendingLocalRecord) { rows[record.otid] = record }
        override suspend fun delete(otid: String) { rows.remove(otid) }
        override suspend fun load(conversationId: String): List<PendingLocalRecord> =
            rows.values.filter { it.conversationId == conversationId }.sortedBy { it.sentAt }
    }

    @Test
    fun `send with attachments writes to disk store synchronously`() = runBlocking {
        val api = FakeSyncApi()
        val store = FakeStore()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv-img", scope, pendingLocalStore = store)

        val image = MessageContentPart.Image(base64 = "AAAA", mediaType = "image/png")
        val otid = sync.send(content = "look at this", attachments = listOf(image))

        // save() is invoked inline within send(); no scheduler advancement needed.
        assertEquals(1, store.rows.size)
        val saved = store.rows[otid]!!
        assertEquals("conv-img", saved.conversationId)
        assertEquals("look at this", saved.content)
        assertEquals(1, saved.attachments.size)
        assertEquals("AAAA", saved.attachments.first().base64)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `hydrate restores disk-persisted Local when server has no matching otid`() = runBlocking {
        // Mirrors the observed Letta behavior (mge5.24): the server stored
        // an assistant reply but no user_message for the image send.
        val api = FakeSyncApi()
        api.addStoredMessage(AssistantMessage(id = "asst-1", contentRaw = JsonPrimitive("got it")))
        val store = FakeStore()
        val image = MessageContentPart.Image(base64 = "BBBB", mediaType = "image/png")
        store.rows["otid-orphan"] = PendingLocalRecord(
            otid = "otid-orphan",
            conversationId = "conv-restore",
            content = "from a previous session",
            attachments = listOf(image),
            sentAt = java.time.Instant.parse("2026-04-19T13:00:00Z"),
        )

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv-restore", scope, pendingLocalStore = store)
        sync.hydrate()

        val restored = sync.state.value.events.filterIsInstance<TimelineEvent.Local>()
            .firstOrNull { it.otid == "otid-orphan" }
        assertNotNull("Expected disk-persisted Local to be restored", restored)
        assertEquals("from a previous session", restored!!.content)
        assertEquals(1, restored.attachments.size)
        assertEquals(DeliveryState.SENT, restored.deliveryState)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `hydrate skips disk-persisted Local when server already has the otid`() = runBlocking {
        // If the server eventually does start persisting image messages (or
        // we left a stale row around for a text send), reconcile/hydrate must
        // NOT double-render the bubble.
        val api = FakeSyncApi()
        api.addStoredMessage(UserMessage(id = "msg-1", contentRaw = JsonPrimitive("here"), otid = "otid-dup"))
        val store = FakeStore()
        store.rows["otid-dup"] = PendingLocalRecord(
            otid = "otid-dup",
            conversationId = "conv-dedup",
            content = "here",
            attachments = emptyList(),
            sentAt = java.time.Instant.parse("2026-04-19T13:00:00Z"),
        )
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv-dedup", scope, pendingLocalStore = store)
        sync.hydrate()

        val withOtid = sync.state.value.events.filter { it.otid == "otid-dup" }
        assertEquals("Expected exactly one event for otid-dup, not a duplicate", 1, withOtid.size)
        assertTrue("Server-confirmed event should win over disk Local", withOtid.first() is TimelineEvent.Confirmed)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `text-only send does not write to store`() = runBlocking {
        val api = FakeSyncApi()
        val store = FakeStore()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv-text", scope, pendingLocalStore = store)

        sync.send(content = "just words")
        scope.coroutineContext.job.cancel()

        assertEquals("Text-only sends must not be persisted", 0, store.rows.size)
    }
}
