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
class TimelineSyncLoopReconcileTest {
    @Test
    fun `reconcile swaps local user event to confirmed`() = runTest {
        val api = FakeSyncApi()
        api.nextStreamMessages = listOf(
            AssistantMessage(id = "reply-1", contentRaw = JsonPrimitive("OK"), otid = "reply-otid")
        )
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

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
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

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
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

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
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

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
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

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
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

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
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-reopen", scope)
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
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-reconcile-gateway", scope)

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
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-live-skip", scope)

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
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-live-refresh", scope)

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
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

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
        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv1", scope)

        // Retry on an otid that doesn't exist at all — must not throw, must not
        // mutate state.
        val retry = async { sync.retry("client-nonexistent") }
        advanceUntilIdle()
        retry.await()
        assertEquals(0, sync.state.value.events.size)

        scope.coroutineContext.job.cancel()
    }

    // --- letta-mobile-mge5.20: tool call / approval / return wiring --------
}
