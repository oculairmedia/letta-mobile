package com.letta.mobile.data.controller.fanout

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * letta-mobile-by4vm.4: the router feeds [RuntimeEventFanout.route] from ONE sequential loop for
 * the whole connection, so route must never wait on a subscriber. A subscriber that falls a full
 * buffer behind is detached on its own (its channel closed with
 * [RuntimeSubscriberOverflowCancellation] after what it already buffered); every other subscriber
 * keeps receiving, in order.
 *
 * FAIL-ON-REVERT: with the old `async { channel.send() }.awaitAll()` delivery the route loop
 * suspends on the stalled subscriber's full channel, so `routeLoop.isCompleted` is false.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeEventFanoutOverflowTest {

    @Test
    fun stalledSubscriberNeverBlocksRouteOrAnotherRuntime() = runTest {
        val fanout = RuntimeEventFanout()
        fanout.subscribe(AgentId(STALLED.agentId), ConversationId(STALLED.conversationId), "stalled")
        val (_, liveEvents) = fanout.subscribe(AgentId(LIVE.agentId), ConversationId(LIVE.conversationId), "live")
        val live = collectInBackground(liveEvents)

        val routeLoop = launch {
            repeat(FRAME_COUNT) { seq ->
                fanout.route(delta(STALLED, seq))
                fanout.route(delta(LIVE, seq))
                // Let the live collector drain; the stalled one is never collected.
                yield()
            }
        }
        runCurrent()

        assertTrue(routeLoop.isCompleted, "route() must return although one subscriber is stalled")
        assertEquals((0 until FRAME_COUNT).toList(), live.frames.map { it.seq() }, "every frame, in order")
        assertNull(live.failure, "the live subscriber is untouched")
        assertEquals(1, fanout.subscriberCount(), "only the stalled subscriber is detached")
    }

    @Test
    fun overflowedSubscriberDrainsItsBufferInOrderThenSeesTheOverflowCause() = runTest {
        val fanout = RuntimeEventFanout()
        val (_, stalledEvents) =
            fanout.subscribe(AgentId(STALLED.agentId), ConversationId(STALLED.conversationId), "stalled")

        repeat(CAPACITY + 1) { seq -> fanout.route(delta(STALLED, seq)) }
        // Frames after the detach go nowhere and do not throw.
        fanout.route(delta(STALLED, CAPACITY + 1))
        assertEquals(0, fanout.subscriberCount())

        val stalled = collectInBackground(stalledEvents)
        runCurrent()

        assertEquals((0 until CAPACITY).toList(), stalled.frames.map { it.seq() })
        val cause = assertIs<RuntimeSubscriberOverflowCancellation>(stalled.failure)
        assertEquals("stalled", cause.subscriberId)
        assertEquals(STALLED.agentId, cause.agentId)
        assertEquals(STALLED.conversationId, cause.conversationId)
        assertEquals("stream_delta", cause.droppedFrameType)
        assertEquals(CAPACITY, cause.capacity)
        assertTrue(cause.isRuntimeStreamDetached(), "turn collectors treat it as a stream detach")
    }

    @Test
    fun overflowReportsTheDetachedRuntimeToTheListener() = runTest {
        val fanout = RuntimeEventFanout()
        val reported = mutableListOf<AppServerRuntimeScope>()
        fanout.bindSubscriberOverflowListener { reported += it }
        fanout.subscribe(AgentId(STALLED.agentId), ConversationId(STALLED.conversationId), "stalled")

        repeat(CAPACITY + 1) { seq -> fanout.route(delta(STALLED, seq)) }

        assertEquals(listOf(STALLED), reported)
    }

    @Test
    fun controlFrameThatOverflowedEveryTargetIsKeptForTheSuccessor() = runTest {
        val fanout = RuntimeEventFanout()
        fanout.subscribe(AgentId(STALLED.agentId), ConversationId(STALLED.conversationId), "stalled")
        repeat(CAPACITY) { seq -> fanout.route(delta(STALLED, seq)) }

        fanout.route(externalToolRequest())
        assertEquals(0, fanout.subscriberCount())
        assertEquals(1, fanout.pendingControlFrameCount(), "the request is retained, not lost")

        val (_, successor) =
            fanout.subscribe(AgentId(STALLED.agentId), ConversationId(STALLED.conversationId), "successor")
        assertEquals(0, fanout.pendingControlFrameCount())
        val frame = assertIs<AppServerInboundFrame.ExternalToolCallRequest>(successor.first().frame)
        assertEquals("ext-1", frame.requestId)
    }

    private class Collected {
        val frames = mutableListOf<AppServerReceivedFrame>()
        var failure: Throwable? = null
    }

    private fun TestScope.collectInBackground(events: Flow<AppServerReceivedFrame>): Collected {
        val collected = Collected()
        backgroundScope.launch {
            events.catch { collected.failure = it }.collect { collected.frames += it }
        }
        return collected
    }

    private companion object {
        const val CAPACITY = RuntimeEventFanout.SUBSCRIBER_BUFFER_CAPACITY
        const val FRAME_COUNT = CAPACITY * 2
        val STALLED = AppServerRuntimeScope("agent-stalled", "conv-stalled")
        val LIVE = AppServerRuntimeScope("agent-live", "conv-live")

        fun delta(runtime: AppServerRuntimeScope, seq: Int) = AppServerReceivedFrame(
            channel = AppServerChannel.Stream,
            frame = AppServerInboundFrame.StreamDelta(
                runtime = runtime,
                eventSeq = seq.toLong(),
                emittedAt = "2026-10-13T00:00:00Z",
                idempotencyKey = "evt-$seq",
                delta = buildJsonObject { put("run_id", JsonPrimitive("run-1")) },
            ),
            raw = buildJsonObject {},
        )

        fun externalToolRequest() = AppServerReceivedFrame(
            channel = AppServerChannel.Control,
            frame = AppServerInboundFrame.ExternalToolCallRequest(
                requestId = "ext-1",
                runtime = STALLED,
                toolCallId = "tc-1",
                toolName = "Echo",
                input = buildJsonObject {},
            ),
            raw = buildJsonObject {},
        )

        fun AppServerReceivedFrame.seq(): Int =
            (frame as AppServerInboundFrame.StreamDelta).eventSeq.toInt()
    }
}
