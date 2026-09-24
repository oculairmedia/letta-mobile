package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.ToolCallPayload
import com.letta.mobile.data.transport.TransportFrameEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * letta-mobile-qygvv.11: exact duplicates are dropped once at the publisher
 * (the transport's single ingest seam), and every subscriber still receives
 * exactly one copy of each distinct frame, sharing one chat projection.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohIngestDedupTest {

    @Test
    fun sameFramePublishedTwiceIsDeliveredOnce() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher()
        val received = mutableListOf<TransportFrameEvent>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { publisher.frameEvents.collect { received += it } }

        val frame = assistantFrame(content = "Hello")
        publisher.publish(frame)
        publisher.publish(frame.copy(ts = "2026-09-24T00:00:09Z", seq = 2))
        runCurrent()

        assertEquals(listOf(frame), received.map { it.frame })
        assertEquals(1L, publisher.ingestDuplicatesDropped)
        job.cancelAndJoin()
    }

    @Test
    fun twoSubscribersEachReceiveOneCopyWithSharedProjection() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher()
        val first = mutableListOf<TransportFrameEvent>()
        val second = mutableListOf<TransportFrameEvent>()
        val firstJob = launch(start = CoroutineStart.UNDISPATCHED) { publisher.frameEvents.collect { first += it } }
        val secondJob = launch(start = CoroutineStart.UNDISPATCHED) { publisher.frameEvents.collect { second += it } }

        val frame = assistantFrame(content = "Hello")
        publisher.publish(frame)
        publisher.publish(frame)
        publisher.publish(assistantFrame(content = "Hello world"))
        runCurrent()

        assertEquals(listOf("Hello", "Hello world"), first.map { (it.frame as ServerFrame.AssistantMessage).content })
        assertEquals(first.map { it.frame }, second.map { it.frame })
        assertSame(first.first().timelineEvent, second.first().timelineEvent)
        firstJob.cancelAndJoin()
        secondJob.cancelAndJoin()
    }

    @Test
    fun toolCallWithChangedArgumentsIsNotDroppedAtIngest() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher()
        val received = mutableListOf<ServerFrame>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { publisher.events.collect { received += it } }

        publisher.publish(toolCallFrame(arguments = "{\"a\":"))
        publisher.publish(toolCallFrame(arguments = "{\"a\":1}"))
        publisher.publish(toolCallFrame(arguments = "{\"a\":1}"))
        runCurrent()

        assertEquals(2, received.size)
        assertEquals(1L, publisher.ingestDuplicatesDropped)
        job.cancelAndJoin()
    }

    @Test
    fun framesWithoutTimelineProjectionAreNeverDropped() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher()
        val received = mutableListOf<ServerFrame>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { publisher.events.collect { received += it } }

        val selfTodo = toolCallFrame(arguments = "{}").copy(runId = "selftodo-run-conv", turnId = "selftodo-turn-conv")
        publisher.publish(selfTodo)
        publisher.publish(selfTodo)
        runCurrent()

        assertEquals(2, received.size)
        assertEquals(0L, publisher.ingestDuplicatesDropped)
        job.cancelAndJoin()
    }

    @Test
    fun resetIngestWindowForgetsPublishedFrames() = runTest(UnconfinedTestDispatcher()) {
        val publisher = IrohFramePublisher()
        val received = mutableListOf<ServerFrame>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { publisher.events.collect { received += it } }

        publisher.publish(assistantFrame(content = "again"))
        publisher.resetIngestWindow()
        publisher.publish(assistantFrame(content = "again"))
        runCurrent()

        assertEquals(2, received.size)
        job.cancelAndJoin()
    }

    private fun assistantFrame(content: String) = ServerFrame.AssistantMessage(
        id = "cm-stream-dedup",
        ts = "2026-09-24T00:00:00Z",
        agentId = "agent-dedup",
        conversationId = "conv-dedup",
        turnId = "turn-dedup",
        runId = "run-dedup",
        content = content,
    )

    private fun toolCallFrame(arguments: String) = ServerFrame.ToolCallMessage(
        id = "toolcall-dedup",
        ts = "2026-09-24T00:00:00Z",
        agentId = "agent-dedup",
        conversationId = "conv-dedup",
        turnId = "turn-dedup",
        runId = "run-dedup",
        toolCall = ToolCallPayload(toolCallId = "call-dedup", name = "Write", arguments = arguments),
    )
}
