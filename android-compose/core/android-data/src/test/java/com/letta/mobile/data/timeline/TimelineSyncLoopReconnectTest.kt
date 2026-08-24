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
class TimelineSyncLoopReconnectTest {
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
        TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-gqz3", scope)

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

        val sync = TimelineSyncLoop(MessageApiTimelineTransport(api), "conv-qv6d", scope)

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
            MessageApiTimelineTransport(api),
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
            messageApi = MessageApiTimelineTransport(api),
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
