package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.ApiException
import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.MessageCreateRequest
import com.letta.mobile.data.model.SystemMessage
import com.letta.mobile.data.model.UserMessage
import io.ktor.utils.io.ByteReadChannel
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    fun `send optimistically appends local event`() = runTest {
        // Uses StandardTestDispatcher (not Unconfined) so the background
        // processSendQueue coroutine is only resumed when we explicitly
        // advance the scheduler. That lets us observe the optimistic Local
        // state before stream+reconcile has a chance to swap it to Confirmed.
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val otid = sync.send("hello")
        // Do NOT advance the scheduler yet — we want the pre-drain snapshot.
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
    fun `send then stream appends confirmed assistant event`() = runBlocking {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val userOtid = sync.send("hello")

        // Wait until the reply lands
        withTimeout(5_000) {
            while (sync.state.value.findByOtid("reply-otid") == null) delay(10)
        }

        val events = sync.state.value.events
        assertEquals(2, events.size)
        assertTrue(events[0].otid == userOtid || events[0].otid.contains(userOtid))
        assertEquals("OK", events[1].content)
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
    fun `streaming assistant text deltas concatenate byte-for-byte under prefix collisions`() = runBlocking {
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
        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        sync.send("hello")

        // Wait for the first frame to land (uniquely identifiable by otid),
        // then for subsequent merges — deltas merge into the same event by
        // serverId, so its content grows monotonically.
        withTimeout(5_000) {
            while (sync.state.value.findByOtid("reply-otid-uww11") == null) delay(10)
            // Then wait until merging is done (content stable for ~50ms)
            var stable = 0
            var lastLen = -1
            while (stable < 5) {
                val ev = sync.state.value.events.firstOrNull {
                    it is TimelineEvent.Confirmed && it.serverId == "reply-stream"
                }
                val len = ev?.content?.length ?: -1
                if (len == lastLen && len >= 0) stable++ else { stable = 0; lastLen = len }
                delay(10)
            }
        }

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
     * lettabot-uww.11 regression: the literal mermaid block from the
     * 2026-04-26 field repro, streamed character-by-character (the
     * worst-case adversarial chunking exercised on the server side
     * by ws-gateway.e2e.test.ts). Asserts byte-perfect reassembly so
     * we'd catch a regression of the original screenshot.
     */
    @Test
    fun `streaming mermaid block character-by-character reassembles byte-perfectly`() = runBlocking {
        val mermaid = "A[LLM snapshots] --> B[Coalesce?]"
        val api = FakeSyncApi()
        api.nextStreamMessages = mermaid.mapIndexed { idx, ch ->
            AssistantMessage(
                id = "reply-mermaid",
                contentRaw = JsonPrimitive(ch.toString()),
                otid = if (idx == 0) "mermaid-otid" else null,
            )
        }
        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        sync.send("draw it")

        withTimeout(5_000) {
            while (sync.state.value.findByOtid("mermaid-otid") == null) delay(10)
            var stable = 0
            var lastLen = -1
            while (stable < 5) {
                val ev = sync.state.value.events.firstOrNull {
                    it is TimelineEvent.Confirmed && it.serverId == "reply-mermaid"
                }
                val len = ev?.content?.length ?: -1
                if (len == lastLen && len >= 0) stable++ else { stable = 0; lastLen = len }
                delay(10)
            }
        }

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
    fun `reconcile swaps local user event to confirmed`() = runBlocking {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val userOtid = sync.send("hello")

        // When stream completes, the fake copies sent message + nextStreamMessages into store
        // with our client otid preserved — reconcile should then find it and swap
        withTimeout(5_000) {
            while (sync.state.value.findByOtid(userOtid) !is TimelineEvent.Confirmed) delay(20)
        }

        val swapped = sync.state.value.findByOtid(userOtid)
        assertTrue(swapped is TimelineEvent.Confirmed)
        assertEquals("hello", swapped!!.content)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `reconcile preserves multimodal attachments on confirmed user event`() = runBlocking {
        val api = FakeSyncApi()
        api.nextStreamMessages = emptyList()
        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val image = MessageContentPart.Image(base64 = "AAAA", mediaType = "image/jpeg")
        val userOtid = sync.send("caption", attachments = listOf(image))

        withTimeout(5_000) {
            while (sync.state.value.findByOtid(userOtid) !is TimelineEvent.Confirmed) delay(20)
        }

        val swapped = sync.state.value.findByOtid(userOtid) as? TimelineEvent.Confirmed
        assertNotNull(swapped)
        assertEquals("caption", swapped!!.content)
        assertEquals(1, swapped.attachments.size)
        assertEquals("image/jpeg", swapped.attachments.first().mediaType)
        assertEquals("AAAA", swapped.attachments.first().base64)
        scope.coroutineContext.job.cancel()
    }

    @Test
    fun `identical content sent twice produces two local events`() = runBlocking {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "r1")
        )
        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val otid1 = sync.send("same")
        val otid2 = sync.send("same")

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
    fun `concurrent sends do not collide on position or otid`() = runBlocking {
        val api = FakeSyncApi()
        api.nextStreamMessages = emptyList()   // no stream body to simplify
        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        // Fire 10 concurrent sends
        val jobs = (1..10).map { i ->
            scope.launch { sync.send("msg $i") }
        }
        jobs.forEach { it.join() }

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
    fun `reconcile recovers when transient listMessages failure is followed by success`() = runBlocking {
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

        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val collectedErrors = mutableListOf<TimelineSyncEvent.ReconcileError>()
        val errorCollector = scope.launch {
            sync.events.collect { ev ->
                if (ev is TimelineSyncEvent.ReconcileError) collectedErrors += ev
            }
        }

        val userOtid = sync.send("hello")

        // Confirmed swap only happens after retry-then-success — if retry
        // were missing, the first 503 would fall straight through to the
        // error branch and the Local would never become Confirmed.
        withTimeout(10_000) {
            while (sync.state.value.findByOtid(userOtid) !is TimelineEvent.Confirmed) delay(20)
        }

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
    fun `reconcile emits ReconcileError when retries are exhausted`() = runBlocking {
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

        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val collectedErrors = mutableListOf<TimelineSyncEvent.ReconcileError>()
        val errorCollector = scope.launch {
            sync.events.collect { ev ->
                if (ev is TimelineSyncEvent.ReconcileError) collectedErrors += ev
            }
        }

        sync.send("hello")

        withTimeout(10_000) {
            while (collectedErrors.isEmpty()) delay(20)
        }

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
    fun `reconcile does not retry on 4xx permanent errors`() = runBlocking {
        // letta-mobile-j44j: 4xx responses (auth, validation) won't become
        // true on retry, so we fail fast to avoid wasting up to 1.4s of
        // backoff sleeping on a permanent error.
        val api = FakeSyncApi()
        api.nextStreamMessages = emptyList()
        api.listMessagesFailuresBeforeSuccess = Int.MAX_VALUE
        api.listMessagesFailure = ApiException(401, "Unauthorized")

        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        val collectedErrors = mutableListOf<TimelineSyncEvent.ReconcileError>()
        val errorCollector = scope.launch {
            sync.events.collect { ev ->
                if (ev is TimelineSyncEvent.ReconcileError) collectedErrors += ev
            }
        }

        sync.send("hello")

        withTimeout(10_000) {
            while (collectedErrors.isEmpty()) delay(20)
        }

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
    fun `retry on non-FAILED event is a no-op`() = runBlocking {
        // letta-mobile-lbmy: the fix moves the read of findByOtid inside
        // writeMutex.withLock. The test here is a behavioural check that
        // retry() on a non-existent otid returns without mutating state —
        // enforcing the read-inside-lock pattern would require instrumenting
        // the mutex which is out of scope; we trust the code review for the
        // TOCTOU property itself.
        val api = FakeSyncApi()
        api.nextStreamMessages = emptyList()
        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        // Retry on an otid that doesn't exist at all — must not throw, must not
        // mutate state.
        sync.retry("client-nonexistent")
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
    fun `streamed tool_return attaches to tool_call event and flips approvalDecided`() = runBlocking {
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
        val scope = CoroutineScope(Dispatchers.IO)
        val sync = TimelineSyncLoop(api, "conv1", scope)

        sync.send("list files")

        // Wait for the stream to drain and ingest.
        withTimeout(2000) {
            while (true) {
                val confirmed = sync.state.value.events.filterIsInstance<TimelineEvent.Confirmed>()
                val tc = confirmed.firstOrNull { it.messageType == TimelineMessageType.TOOL_CALL }
                if (tc != null && tc.toolReturnContent != null) break
                delay(20)
            }
        }

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
     * letta-mobile-gqz3 regression test.
     *
     * Before the fix, the subscriber's `ApiException` catch branch only
     * treated `"No active runs"` as an idle pattern. When a previously-
     * active run aged out, the server returned
     * `EXPIRED: Run was created more than 3 hours ago, and is now expired.`
     * which the subscriber mis-classified as a generic network error,
     * wedging at the backoff cap and never opening a fresh stream.
     *
     * The fix extends the idle-pattern match to include `EXPIRED:` /
     * `is now expired`, so the subscriber backs off once and retries on the
     * next iteration — exactly the same posture as `No active runs`.
     *
     * Assertion: observing an EXPIRED ApiException must emit the
     * `streamSubscriber.idle404` Telemetry event (with via=apiException)
     * and must NOT emit a `streamSubscriber.error` for the same cycle.
     */
    @Test
    fun `EXPIRED run ApiException is classified as idle, not error`() = runBlocking {
        // Clear the Telemetry ring so we can look at an empty slate. This is
        // necessary because other tests sharing the same JVM worker may have
        // left unrelated events behind.
        com.letta.mobile.util.Telemetry.clear()

        val api = ExpiredThenIdleApi()
        val scope = CoroutineScope(Dispatchers.Unconfined)

        // Instantiating TimelineSyncLoop starts the subscriber coroutine in init{}.
        val sync = TimelineSyncLoop(api, "conv-gqz3", scope)

        // Let the subscriber attempt one stream call. Because the fake throws
        // immediately, this should flow through the ApiException branch.
        withTimeout(2_000) {
            while (api.streamCallCount < 1) delay(10)
        }
        // Let the catch branch complete its Telemetry emission.
        delay(100)

        // Telemetry ring is newest-first (addFirst semantics), so we just
        // filter the whole thing by (tag, name) rather than slicing.
        val events = com.letta.mobile.util.Telemetry.snapshot()
        val idle404 = events.firstOrNull {
            it.tag == "TimelineSync" &&
                it.name == "streamSubscriber.idle404" &&
                (it.attrs["via"] as? String) == "apiException" &&
                (it.attrs["conversationId"] as? String) == "conv-gqz3"
        }
        assertNotNull(
            "EXPIRED ApiException must be classified as idle (streamSubscriber.idle404 via=apiException). " +
                "Saw events: ${events.map { "${it.tag}/${it.name}" }}",
            idle404,
        )
        // And explicitly: there must not be a streamSubscriber.error for this
        // cycle — before the fix, the wedge produced one error per retry.
        val errorForGqz3 = events.firstOrNull {
            it.tag == "TimelineSync" &&
                it.name == "streamSubscriber.error" &&
                (it.attrs["conversationId"] as? String) == "conv-gqz3"
        }
        assertEquals(
            "EXPIRED must NOT produce a streamSubscriber.error (it's an idle pattern, not a real failure)",
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
}

/**
 * Fake api for the qv6d regression test. Every streamConversation call
 * throws the server's "no active runs" idle pattern. Lets the subscriber
 * loop iterate as fast as the test scope's Unconfined dispatcher allows,
 * so we can observe the backoff doubling in telemetry.
 */
private class AlwaysIdleApi : MessageApi(mockk(relaxed = true)) {
    @Volatile var streamCallCount: Int = 0

    override suspend fun streamConversation(conversationId: String): ByteReadChannel {
        streamCallCount++
        throw ApiException(
            400,
            """{"detail":"No active runs found for conversation."}""",
        )
    }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> = emptyList()
}

/**
 * Fake api for the gqz3 regression test. Throws an `ApiException` carrying
 * the server's EXPIRED detail string on the first `streamConversation`
 * call, then idles (awaits cancellation) so the subscriber's backoff loop
 * can settle without a stream of identical failures.
 */
private class ExpiredThenIdleApi : MessageApi(mockk(relaxed = true)) {
    @Volatile var streamCallCount: Int = 0

    override suspend fun streamConversation(conversationId: String): ByteReadChannel {
        streamCallCount++
        if (streamCallCount == 1) {
            throw ApiException(
                400,
                """{"detail":"EXPIRED: Run was created more than 3 hours ago, and is now expired."}""",
            )
        }
        kotlinx.coroutines.awaitCancellation()
    }

    override suspend fun listConversationMessages(
        conversationId: String,
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

    // letta-mobile-j44j: failure-injection for reconcile retry tests.
    // When [listMessagesFailuresBeforeSuccess] > 0, the first N calls to
    // [listConversationMessages] throw [listMessagesFailure] (or a default
    // IOException if none is set). Subsequent calls return normally.
    var listMessagesFailuresBeforeSuccess: Int = 0
    var listMessagesFailure: Throwable? = null
    var listMessagesCalls: Int = 0

    fun addStoredMessage(msg: LettaMessage) {
        stored.add(msg)
    }

    // The default `MessageApi.streamConversation` calls into a relaxed-mockk
    // HttpClient and returns an ApiException on every invocation, which sends
    // `runStreamSubscriber` into a 5s-delay retry loop that accumulates
    // timers for the full duration of each test (observed: 91s for one test
    // that never exercises the subscriber). Idle here instead so the loop
    // suspends until the test's scope is cancelled. letta-mobile-o8pr.
    override suspend fun streamConversation(conversationId: String): ByteReadChannel {
        kotlinx.coroutines.awaitCancellation()
    }

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        listMessagesCalls++
        if (listMessagesFailuresBeforeSuccess > 0) {
            listMessagesFailuresBeforeSuccess--
            throw listMessagesFailure
                ?: java.io.IOException("injected listConversationMessages failure")
        }
        return if (order == "desc") stored.reversed() else stored.toList()
    }

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: MessageCreateRequest,
    ): ByteReadChannel {
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
    fun `assistant confirmed catch-up does not re-append content already present in collapsed client mode local`() = runBlocking {
        val api = FakeSyncApi()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv-assist-dedup", scope)
        try {
            sync.hydrate()
            sync.upsertClientModeLocalAssistantChunk(
                localId = "cm-assist-1",
                build = {
                    TimelineEvent.Local(
                        position = 0.0,
                        otid = "cm-assist-1",
                        content = "Hello world",
                        role = Role.ASSISTANT,
                        sentAt = java.time.Instant.parse("2026-04-29T10:00:00Z"),
                        deliveryState = DeliveryState.SENT,
                        source = MessageSource.CLIENT_MODE_HARNESS,
                        messageType = TimelineMessageType.ASSISTANT,
                    )
                },
                transform = { it },
            )

            sync.ingestStreamEvent(
                AssistantMessage(
                    id = "srv-assist-1",
                    contentRaw = JsonPrimitive("world"),
                    date = "2026-04-29T10:00:00Z",
                    runId = "run-1",
                )
            )

            sync.ingestStreamEvent(
                AssistantMessage(
                    id = "srv-assist-1",
                    contentRaw = JsonPrimitive("world"),
                    date = "2026-04-29T10:00:01Z",
                    runId = "run-1",
                )
            )

            sync.ingestStreamEvent(
                AssistantMessage(
                    id = "srv-assist-1",
                    contentRaw = JsonPrimitive("!"),
                    date = "2026-04-29T10:00:02Z",
                    runId = "run-1",
                )
            )

            val final = sync.state.value.events
                .filterIsInstance<TimelineEvent.Confirmed>()
                .firstOrNull { it.serverId == "srv-assist-1" }
            assertNotNull("assistant confirmed event should exist after catch-up", final)
            assertEquals("Hello world!", final!!.content)
            assertEquals(MessageSource.LETTA_SERVER, final.source)
        } finally {
            scope.coroutineContext.job.cancel()
        }
    }

    @Test
    fun `text-only send does not write to store`() = runBlocking {
        val api = FakeSyncApi()
        val store = FakeStore()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val sync = TimelineSyncLoop(api, "conv-text", scope, pendingLocalStore = store)

        sync.send(content = "just words")
        kotlinx.coroutines.delay(50)
        scope.coroutineContext.job.cancel()

        assertEquals("Text-only sends must not be persisted", 0, store.rows.size)
    }

    /**
     * Doubled-response fix regression test (plan:
     * 2026-04-clientmode-double-bubble-fix.md).
     *
     * When [SubscriberSuppressionGate.isSuppressed] returns true for the
     * loop's conversationId, the subscriber MUST NOT call
     * [MessageApi.streamConversation]. This is the core invariant that
     * stops the duplicate Confirmed bubble in Client Mode.
     *
     * We use a stricter assertion than \"streamCallCount stays low\":
     *  - drive the subscriber for several STREAM_DORMANT_MS windows,
     *  - assert ZERO stream opens occurred,
     *  - assert exactly ONE \"streamSubscriber.suppressed\" telemetry event
     *    was emitted (state-transition, not per-tick).
     */
    @Test
    fun `subscriber stays dormant when suppression gate returns true`() = runTest {
        com.letta.mobile.util.Telemetry.clear()
        val api = AlwaysIdleApi()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        // Always-suppress gate.
        val gate = SubscriberSuppressionGate { true }

        val sync = TimelineSyncLoop(
            messageApi = api,
            conversationId = "conv-suppressed",
            scope = scope,
            subscriberSuppression = gate,
        )

        // Drive several dormant cycles (STREAM_DORMANT_MS = 3_000L).
        repeat(5) {
            testScheduler.advanceTimeBy(3_500)
            testScheduler.runCurrent()
        }

        assertEquals(
            "Suppressed subscriber must not open any direct-Letta streams",
            0,
            api.streamCallCount,
        )

        val events = com.letta.mobile.util.Telemetry.snapshot()
        val suppressedEvents = events.filter {
            it.tag == "TimelineSync" &&
                it.name == "streamSubscriber.suppressed" &&
                (it.attrs["conversationId"] as? String) == "conv-suppressed"
        }
        assertEquals(
            "Should emit exactly one streamSubscriber.suppressed event " +
                "(state-transition, not per-tick). Saw: $suppressedEvents",
            1,
            suppressedEvents.size,
        )
        scope.coroutineContext.job.cancel()
        Unit
    }

    /**
     * When the gate flips false after a suppression window, the subscriber
     * MUST resume — opening a direct-Letta SSE on the next loop tick. This
     * guards against the suppression hook accidentally turning into a
     * one-way kill switch.
     */
    @Test
    fun `subscriber resumes when suppression gate flips false`() = runTest {
        com.letta.mobile.util.Telemetry.clear()
        val api = AlwaysIdleApi()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val suppressed = java.util.concurrent.atomic.AtomicBoolean(true)
        val gate = SubscriberSuppressionGate { suppressed.get() }

        val sync = TimelineSyncLoop(
            messageApi = api,
            conversationId = "conv-flipping",
            scope = scope,
            subscriberSuppression = gate,
        )

        // Sit in the suppressed branch for two dormant windows.
        repeat(2) {
            testScheduler.advanceTimeBy(3_500)
            testScheduler.runCurrent()
        }
        assertEquals(0, api.streamCallCount)

        // Flip the gate. Next loop tick should hit the resume branch.
        suppressed.set(false)
        // Walk forward enough to (a) wake from the dormant delay and (b)
        // run at least one stream open + idle backoff cycle.
        repeat(5) {
            testScheduler.advanceTimeBy(3_500)
            testScheduler.runCurrent()
        }

        assertTrue(
            "Subscriber must resume (open ≥ 1 stream) after gate flips false; saw ${api.streamCallCount}",
            api.streamCallCount >= 1,
        )
        val lifted = com.letta.mobile.util.Telemetry.snapshot().firstOrNull {
            it.tag == "TimelineSync" &&
                it.name == "streamSubscriber.suppressionLifted" &&
                (it.attrs["conversationId"] as? String) == "conv-flipping"
        }
        assertNotNull(
            "Lifting suppression must emit streamSubscriber.suppressionLifted",
            lifted,
        )
        scope.coroutineContext.job.cancel()
        Unit
    }

    /**
     * Default behaviour (no gate parameter passed) MUST be never-suppress —
     * otherwise every existing call site in the codebase would silently
     * stop receiving SSE. This test pins the default constructor argument.
     */
    @Test
    fun `default constructor never suppresses the subscriber`() = runTest {
        val api = AlwaysIdleApi()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)

        // Note: NO subscriberSuppression argument — must default to
        // SubscriberSuppressionGate.Always (which returns false).
        val sync = TimelineSyncLoop(api, "conv-default", scope)

        repeat(3) {
            testScheduler.advanceTimeBy(3_500)
            testScheduler.runCurrent()
        }
        assertTrue(
            "Default subscriber must open at least one stream; saw ${api.streamCallCount}",
            api.streamCallCount >= 1,
        )
        scope.coroutineContext.job.cancel()
        Unit
    }
}
