package com.letta.mobile.data.controller.fanout

import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Duration.Companion.milliseconds

class AppServerRuntimeEventRouterTest {
    @Test
    fun attachRoutesInboundEventsToSubscribers() = runTest {
        val inbound = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 8)
        val router = AppServerRuntimeEventRouter()
        router.attach(this, inbound)

        val (_, events) = router.subscribe(AgentId("agent-1"), ConversationId("conv-1"))

        val frame = AppServerInboundFrame.StreamDelta(
            runtime = AppServerRuntimeScope("agent-1", "conv-1"),
            eventSeq = 1,
            emittedAt = "2026-06-27T00:00:00Z",
            idempotencyKey = "evt-1",
            delta = JsonPrimitive("delta"),
        )
        val received = AppServerReceivedFrame(
            channel = AppServerChannel.Stream,
            frame = frame,
            raw = buildJsonObject {},
        )

        val results = mutableListOf<AppServerReceivedFrame>()
        val collector = launch {
            events.collect { results += it }
        }
        delay(50.milliseconds)

        inbound.emit(received)
        delay(50.milliseconds)

        assertEquals(1, results.size)
        assertEquals(frame, results.single().frame)
        collector.cancel()
        router.detach()
    }

    @Test
    fun detachStopsRouting() = runTest {
        val inbound = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 8)
        val router = AppServerRuntimeEventRouter()
        router.attach(this, inbound)

        val (_, events) = router.subscribe(AgentId("agent-1"), ConversationId("conv-1"))
        router.detach()

        val frame = AppServerInboundFrame.StreamDelta(
            runtime = AppServerRuntimeScope("agent-1", "conv-1"),
            eventSeq = 1,
            emittedAt = "2026-06-27T00:00:00Z",
            idempotencyKey = "evt-1",
            delta = JsonPrimitive("delta"),
        )

        events.test {
            val error = awaitError()
            assertEquals(
                true,
                error is kotlinx.coroutines.CancellationException,
                "detach must fail active subscribers rather than clean-complete",
            )
        }
        // Upstream emissions after detach must not reach a closed subscriber.
        inbound.emit(
            AppServerReceivedFrame(
                channel = AppServerChannel.Stream,
                frame = frame,
                raw = buildJsonObject {},
            ),
        )
    }

    @Test
    fun closeAllSubscribersSyncDoesNotSkipUnderConcurrentSubscribe() = runTest {
        val fanout = RuntimeEventFanout()
        val (_, events) = fanout.subscribe(AgentId("agent-1"), ConversationId("conv-1"))
        val subscribePressure = launch {
            repeat(32) { i ->
                fanout.subscribe(AgentId("agent-$i"), ConversationId("conv-$i"))
            }
        }
        delay(5.milliseconds)
        // Blocking state lock must still run cleanup (never tryLock-skip).
        fanout.closeAllSubscribersSync()
        subscribePressure.join()

        events.test {
            val error = awaitError()
            assertEquals(
                true,
                error is kotlinx.coroutines.CancellationException,
                "closeAllSubscribersSync must close existing subscribers under subscribe pressure",
            )
        }
    }
}