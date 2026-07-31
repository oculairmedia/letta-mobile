package com.letta.mobile.data.controller.fanout

import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerLoopStatus
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

import kotlin.time.Duration.Companion.milliseconds
class RuntimeEventFanoutTest {
    @Test
    fun subscribeReturnsFlowForRuntime() = runTest {
        val fanout = RuntimeEventFanout()
        val agentId = AgentId("agent-1")
        val conversationId = ConversationId("conv-1")

        val (subscriberId, events) = fanout.subscribe(agentId, conversationId)

        assertEquals(1, fanout.subscriberCount())
        assertEquals(1, fanout.runtimeFlowCount())
        assertTrue(subscriberId.startsWith("subscriber-"))
    }

    @Test
    fun multipleSubscribersToSameRuntimeReceiveSameEvents() = runTest {
        val fanout = RuntimeEventFanout()
        val agentId = AgentId("agent-1")
        val conversationId = ConversationId("conv-1")

        val (sub1Id, events1) = fanout.subscribe(agentId, conversationId, "sub-1")
        val (sub2Id, events2) = fanout.subscribe(agentId, conversationId, "sub-2")

        assertEquals(2, fanout.subscriberCount())
        assertEquals(1, fanout.runtimeFlowCount()) // Only one flow for the runtime
        assertEquals(2, fanout.subscriberCountForRuntime(agentId, conversationId))

        // Both subscribers should receive the same events
        val streamDelta = buildStreamDelta(agentId.value, conversationId.value, "run-1")

        // Test both flows - they share the same underlying flow
        val results = mutableListOf<AppServerReceivedFrame>()
        
        val job1 = launch {
            events1.test {
                results.add(awaitItem())
            }
        }
        
        val job2 = launch {
            events2.test {
                results.add(awaitItem())
            }
        }
        
        // Give subscribers time to start collecting
        delay(50.milliseconds)
        
        fanout.route(received(streamDelta))
        
        job1.join()
        job2.join()

        assertEquals(2, results.size)
        assertEquals(streamDelta, results[0].frame)
        assertEquals(streamDelta, results[1].frame)
    }

    @Test
    fun subscribersToDifferentRuntimesReceiveIsolatedEvents() = runTest {
        val fanout = RuntimeEventFanout()

        // Subscribe to runtime A
        val (subA, eventsA) = fanout.subscribe(AgentId("agent-A"), ConversationId("conv-A"), "sub-A")

        // Subscribe to runtime B
        val (subB, eventsB) = fanout.subscribe(AgentId("agent-B"), ConversationId("conv-B"), "sub-B")

        assertEquals(2, fanout.subscriberCount())
        assertEquals(2, fanout.runtimeFlowCount())

        // Route event to runtime A
        val eventForA = buildStreamDelta("agent-A", "conv-A", "run-1")

        // Route event to runtime B
        val eventForB = buildStreamDelta("agent-B", "conv-B", "run-2")

        val receivedA = mutableListOf<AppServerReceivedFrame>()
        val receivedB = mutableListOf<AppServerReceivedFrame>()

        val jobA = launch {
            eventsA.test {
                receivedA.add(awaitItem())
                receivedA.add(awaitItem()) // Should timeout waiting for second event
            }
        }

        val jobB = launch {
            eventsB.test {
                receivedB.add(awaitItem())
                receivedB.add(awaitItem()) // Should timeout waiting for second event
            }
        }

        // Give subscribers time to start collecting
        delay(50.milliseconds)

        // Send event to runtime A
        fanout.route(received(eventForA))
        delay(50.milliseconds)

        // Send event to runtime B
        fanout.route(received(eventForB))
        delay(50.milliseconds)

        // Only subscriber A should have received eventForA
        assertEquals(1, receivedA.size)
        assertEquals(eventForA, receivedA[0].frame)

        // Only subscriber B should have received eventForB
        assertEquals(1, receivedB.size)
        assertEquals(eventForB, receivedB[0].frame)

        jobA.cancel()
        jobB.cancel()
    }

    @Test
    fun routeContinuesAfterConcurrentStyleClosedSubscriber() = runTest {
        // After unsubscribe closes a channel, routing to remaining subscribers must
        // still succeed (ClosedSendChannelException must not escape route()).
        val fanout = RuntimeEventFanout()
        val agentId = AgentId("agent-1")
        val conversationId = ConversationId("conv-1")

        val (closedId, _) = fanout.subscribe(agentId, conversationId, "closed")
        val (_, liveEvents) = fanout.subscribe(agentId, conversationId, "live")
        fanout.unsubscribe(closedId)

        liveEvents.test {
            fanout.route(received(buildStreamDelta(agentId.value, conversationId.value, "run-1")))
            assertEquals(
                buildStreamDelta(agentId.value, conversationId.value, "run-1"),
                awaitItem().frame,
            )
        }
    }

    @Test
    fun unsubscribeRemovesSubscriber() = runTest {
        val fanout = RuntimeEventFanout()
        val agentId = AgentId("agent-1")
        val conversationId = ConversationId("conv-1")

        val (subscriberId, events) = fanout.subscribe(agentId, conversationId)

        assertEquals(1, fanout.subscriberCount())
        assertEquals(1, fanout.runtimeFlowCount())

        val removed = fanout.unsubscribe(subscriberId)

        assertTrue(removed)
        assertEquals(0, fanout.subscriberCount())
        assertEquals(0, fanout.runtimeFlowCount()) // Flow cleaned up
    }

    @Test
    fun unsubscribeLastSubscriberCleansUpRuntimeFlow() = runTest {
        val fanout = RuntimeEventFanout()
        val agentId = AgentId("agent-1")
        val conversationId = ConversationId("conv-1")

        val (sub1, _) = fanout.subscribe(agentId, conversationId, "sub-1")
        val (sub2, _) = fanout.subscribe(agentId, conversationId, "sub-2")

        assertEquals(2, fanout.subscriberCount())
        assertEquals(1, fanout.runtimeFlowCount())

        // Unsubscribe first subscriber
        fanout.unsubscribe(sub1)

        assertEquals(1, fanout.subscriberCount())
        assertEquals(1, fanout.runtimeFlowCount()) // Flow still exists

        // Unsubscribe second (last) subscriber
        fanout.unsubscribe(sub2)

        assertEquals(0, fanout.subscriberCount())
        assertEquals(0, fanout.runtimeFlowCount()) // Flow cleaned up
    }

    @Test
    fun unsubscribeNonExistentSubscriberReturnsFalse() = runTest {
        val fanout = RuntimeEventFanout()

        val removed = fanout.unsubscribe("non-existent")

        assertFalse(removed)
    }

    @Test
    fun routeDropsEventsWithNoRuntime() = runTest {
        val fanout = RuntimeEventFanout()
        val agentId = AgentId("agent-1")
        val conversationId = ConversationId("conv-1")

        val (_, events) = fanout.subscribe(agentId, conversationId)

        // Route a frame with no runtime (e.g., RuntimeStartResponse)
        val frameWithoutRuntime = AppServerInboundFrame.RuntimeStartResponse(
            requestId = "req-1",
            success = true,
            runtime = null,
        )

        events.test {
            fanout.route(received(frameWithoutRuntime))

            // No events should be received
            expectNoEvents()
        }
    }

    @Test
    fun routeDropsEventsForRuntimesWithNoSubscribers() = runTest {
        val fanout = RuntimeEventFanout()

        // Subscribe to runtime A
        val (_, eventsA) = fanout.subscribe(AgentId("agent-A"), ConversationId("conv-A"))

        // Route event to runtime B (no subscribers)
        val eventForB = buildStreamDelta("agent-B", "conv-B", "run-1")

        eventsA.test {
            fanout.route(received(eventForB))

            // Subscriber A should not receive event for runtime B
            expectNoEvents()
        }
    }

    @Test
    fun turnLockSerializesWorkOnSameRuntime() = runTest {
        val fanout = RuntimeEventFanout()
        val agentId = AgentId("agent-1")
        val conversationId = ConversationId("conv-1")

        var turn1Started = false
        var turn1Completed = false
        var turn2Started = false
        var turn2Completed = false

        // Launch turn 1
        val turn1 = async {
            fanout.withTurnLock(agentId, conversationId) {
                turn1Started = true
                delay(100.milliseconds) // Simulate work
                turn1Completed = true
            }
        }

        // Launch turn 2 (should wait for turn 1)
        val turn2 = async {
            delay(10.milliseconds) // Ensure turn 1 acquires lock first
            fanout.withTurnLock(agentId, conversationId) {
                turn2Started = true
                // Turn 1 should be completed by now
                assertTrue(turn1Completed, "Turn 1 should complete before turn 2 starts")
                turn2Completed = true
            }
        }

        turn1.await()
        turn2.await()

        assertTrue(turn1Started)
        assertTrue(turn1Completed)
        assertTrue(turn2Started)
        assertTrue(turn2Completed)
    }

    @Test
    fun turnLockAllowsParallelWorkOnDifferentRuntimes() = runTest {
        val fanout = RuntimeEventFanout()

        var turn1Started = false
        var turn1Completed = false
        var turn2Started = false
        var turn2Completed = false

        // Launch turn on runtime A
        val turn1 = async {
            fanout.withTurnLock(AgentId("agent-A"), ConversationId("conv-A")) {
                turn1Started = true
                delay(100.milliseconds) // Simulate work
                turn1Completed = true
            }
        }

        // Launch turn on runtime B (should NOT wait for turn 1)
        val turn2 = async {
            delay(10.milliseconds) // Ensure turn 1 acquires lock first
            fanout.withTurnLock(AgentId("agent-B"), ConversationId("conv-B")) {
                turn2Started = true
                // Turn 1 should NOT be completed yet (running in parallel)
                assertFalse(turn1Completed, "Turn 1 should not be completed (parallel execution)")
                delay(50.milliseconds)
                turn2Completed = true
            }
        }

        turn2.await()
        turn1.await()

        assertTrue(turn1Started)
        assertTrue(turn1Completed)
        assertTrue(turn2Started)
        assertTrue(turn2Completed)
    }

    @Test
    fun turnLockReleasedOnException() = runTest {
        val fanout = RuntimeEventFanout()
        val agentId = AgentId("agent-1")
        val conversationId = ConversationId("conv-1")

        // First turn throws an exception
        try {
            fanout.withTurnLock(agentId, conversationId) {
                throw RuntimeException("Test exception")
            }
        } catch (e: RuntimeException) {
            // Expected
        }

        // Second turn should acquire the lock (lock was released despite exception)
        var turn2Executed = false
        fanout.withTurnLock(agentId, conversationId) {
            turn2Executed = true
        }

        assertTrue(turn2Executed)
    }

    @Test
    fun routeAllEventTypes() = runTest {
        val fanout = RuntimeEventFanout()
        val agentId = AgentId("agent-1")
        val conversationId = ConversationId("conv-1")

        val (_, events) = fanout.subscribe(agentId, conversationId)

        val runtime = AppServerRuntimeScope(agentId.value, conversationId.value)

        events.test {
            // StreamDelta
            val streamDelta = AppServerInboundFrame.StreamDelta(
                runtime = runtime,
                eventSeq = 1,
                emittedAt = "2026-06-27T00:00:00Z",
                idempotencyKey = "evt-1",
                delta = JsonPrimitive("delta"),
            )
            fanout.route(received(streamDelta))
            assertEquals(streamDelta, awaitItem().frame)

            // UpdateLoopStatus
            val updateLoopStatus = AppServerInboundFrame.UpdateLoopStatus(
                runtime = runtime,
                eventSeq = 2,
                emittedAt = "2026-06-27T00:00:00Z",
                idempotencyKey = "evt-2",
                loopStatus = AppServerLoopStatus(status = "active"),
            )
            fanout.route(received(updateLoopStatus))
            assertEquals(updateLoopStatus, awaitItem().frame)

            // UpdateDeviceStatus
            val updateDeviceStatus = AppServerInboundFrame.UpdateDeviceStatus(
                runtime = runtime,
                eventSeq = 3,
                emittedAt = "2026-06-27T00:00:00Z",
                idempotencyKey = "evt-3",
                deviceStatus = buildJsonObject {},
            )
            fanout.route(received(updateDeviceStatus))
            assertEquals(updateDeviceStatus, awaitItem().frame)

            // UpdateQueue
            val updateQueue = AppServerInboundFrame.UpdateQueue(
                runtime = runtime,
                eventSeq = 4,
                emittedAt = "2026-06-27T00:00:00Z",
                idempotencyKey = "evt-4",
                queue = emptyList(),
            )
            fanout.route(received(updateQueue))
            assertEquals(updateQueue, awaitItem().frame)

            // UpdateSubagentState
            val updateSubagentState = AppServerInboundFrame.UpdateSubagentState(
                runtime = runtime,
                eventSeq = 5,
                emittedAt = "2026-06-27T00:00:00Z",
                idempotencyKey = "evt-5",
                subagents = emptyList(),
            )
            fanout.route(received(updateSubagentState))
            assertEquals(updateSubagentState, awaitItem().frame)

            expectNoEvents()
        }
    }

    @Test
    fun subscriberCountForRuntimeTracksCorrectRuntime() = runTest {
        val fanout = RuntimeEventFanout()

        val agentA = AgentId("agent-A")
        val convA = ConversationId("conv-A")
        val agentB = AgentId("agent-B")
        val convB = ConversationId("conv-B")

        // Subscribe twice to runtime A
        fanout.subscribe(agentA, convA, "sub-A1")
        fanout.subscribe(agentA, convA, "sub-A2")

        // Subscribe once to runtime B
        fanout.subscribe(agentB, convB, "sub-B1")

        assertEquals(2, fanout.subscriberCountForRuntime(agentA, convA))
        assertEquals(1, fanout.subscriberCountForRuntime(agentB, convB))
    }

    @Test
    fun lateSubscriberDoesNotReplayPriorTerminal() = runTest {
        val fanout = RuntimeEventFanout()
        val agentId = AgentId("agent-1")
        val conversationId = ConversationId("conv-1")

        val (_, events1) = fanout.subscribe(agentId, conversationId, "sub-1")
        val terminal = AppServerInboundFrame.UpdateLoopStatus(
            runtime = AppServerRuntimeScope(agentId.value, conversationId.value),
            eventSeq = 9,
            emittedAt = "2026-06-27T00:00:00Z",
            idempotencyKey = "terminal-1",
            loopStatus = AppServerLoopStatus(status = "idle"),
        )

        // Keep the original runtime subscription alive so this asserts against
        // the live shared routing path (not a fresh empty map after unsubscribe).
        events1.test {
            fanout.route(received(terminal))
            assertEquals(terminal, awaitItem().frame)

            val (_, events2) = fanout.subscribe(agentId, conversationId, "sub-2")
            events2.test {
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun framesRoutedAfterSubscribeAreBufferedBeforeCollect() = runTest {
        val fanout = RuntimeEventFanout()
        val agentId = AgentId("agent-1")
        val conversationId = ConversationId("conv-1")
        val (_, events) = fanout.subscribe(agentId, conversationId, "sub-1")
        val frame = buildStreamDelta(agentId.value, conversationId.value, "run-early")

        // Route before any collector attaches — must not be dropped.
        fanout.route(received(frame))

        events.test {
            assertEquals(frame, awaitItem().frame)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun lastViewerUnsubscribeKeepsTurnLockUntilReleased() = runTest {
        val fanout = RuntimeEventFanout()
        val agentId = AgentId("agent-1")
        val conversationId = ConversationId("conv-1")

        val (subId, _) = fanout.subscribe(agentId, conversationId, "viewer-1")
        fanout.acquireTurnLock(agentId, conversationId)
        assertEquals(1, fanout.turnLockCount())

        fanout.unsubscribe(subId)
        assertEquals(0, fanout.runtimeFlowCount())
        assertEquals(1, fanout.turnLockCount(), "turn lock must survive last viewer unsubscribe")

        fanout.releaseTurnLock(agentId, conversationId)
        assertEquals(0, fanout.turnLockCount(), "idle lock with no viewers must retire")
    }

    @Test
    fun unscopedExternalToolRequestIsDeliveredOnceAndDeduped() = runTest {
        val fanout = RuntimeEventFanout()
        val (_, eventsA) = fanout.subscribe(AgentId("agent-A"), ConversationId("conv-A"), "sub-A")
        val (_, eventsB) = fanout.subscribe(AgentId("agent-B"), ConversationId("conv-B"), "sub-B")

        val request = AppServerInboundFrame.ExternalToolCallRequest(
            requestId = "ext-1",
            runtime = null,
            toolCallId = "tc-1",
            toolName = "Echo",
            input = buildJsonObject {},
        )

        val received = mutableListOf<String>()
        val jobA = launch {
            eventsA.test {
                received.add("A:" + (awaitItem().frame as AppServerInboundFrame.ExternalToolCallRequest).requestId)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
        val jobB = launch {
            eventsB.test {
                received.add("B:" + (awaitItem().frame as AppServerInboundFrame.ExternalToolCallRequest).requestId)
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }
        delay(50.milliseconds)
        fanout.route(received(request))
        // Dispatched (buffered, not yet claimed) stays retriable — a turn claim
        // is what locks out further fanout delivery of the same request_id.
        assertTrue(
            fanout.inboundControlRegistry().tryClaim(
                // lgns8.22.4.1.3: external-tool identity is (request_id, tool_call_id).
                controlRef("ext-1", "tc-1"),
                leaseToken = 1L,
                connectionGeneration = 0L,
            ),
        )
        fanout.route(received(request))
        delay(50.milliseconds)
        jobA.cancel()
        jobB.cancel()
        jobA.join()
        jobB.join()

        assertEquals(2, received.size)
        assertTrue(received.contains("A:ext-1"))
        assertTrue(received.contains("B:ext-1"))
        assertEquals(1, fanout.inboundControlRegistry().pendingCount())
    }

    @Test
    fun unscopedExternalToolDispatchedWithoutClaimAllowsReplayDelivery() = runTest {
        val fanout = RuntimeEventFanout()
        val (_, events) = fanout.subscribe(AgentId("agent-A"), ConversationId("conv-A"), "sub-A")
        val request = AppServerInboundFrame.ExternalToolCallRequest(
            requestId = "ext-replay",
            runtime = null,
            toolCallId = "tc-replay",
            toolName = "Echo",
            input = buildJsonObject {},
        )
        val ids = mutableListOf<String>()
        val job = launch {
            events.test {
                ids.add((awaitItem().frame as AppServerInboundFrame.ExternalToolCallRequest).requestId)
                ids.add((awaitItem().frame as AppServerInboundFrame.ExternalToolCallRequest).requestId)
                cancelAndIgnoreRemainingEvents()
            }
        }
        delay(50.milliseconds)
        fanout.route(received(request))
        // No claim — Dispatched duplicate must still deliver (subscriber cancelled
        // before matches() would otherwise leave the request stuck forever).
        fanout.route(received(request))
        delay(50.milliseconds)
        job.cancel()
        job.join()
        assertEquals(listOf("ext-replay", "ext-replay"), ids)
    }

    @Test
    fun unscopedControlIsNotRegisteredWhenNoSubscribersExist() = runTest {
        val fanout = RuntimeEventFanout()
        val request = AppServerInboundFrame.ExternalToolCallRequest(
            requestId = "ext-early",
            runtime = null,
            toolCallId = "tc-early",
            toolName = "Echo",
            input = buildJsonObject {},
        )
        fanout.route(received(request))
        assertEquals(0, fanout.inboundControlRegistry().pendingCount())

        val (_, events) = fanout.subscribe(AgentId("agent-1"), ConversationId("conv-1"), "sub-1")
        events.test {
            fanout.route(received(request))
            assertEquals("ext-early", (awaitItem().frame as AppServerInboundFrame.ExternalToolCallRequest).requestId)
            cancelAndIgnoreRemainingEvents()
        }
        assertEquals(1, fanout.inboundControlRegistry().pendingCount())
    }
}

private fun received(frame: AppServerInboundFrame): AppServerReceivedFrame =
    AppServerReceivedFrame(
        channel = AppServerChannel.Stream,
        frame = frame,
        raw = buildJsonObject {},
    )

/**
 * Helper to build a StreamDelta frame for testing.
 */
private fun buildStreamDelta(
    agentId: String,
    conversationId: String,
    runId: String,
): AppServerInboundFrame.StreamDelta =
    AppServerInboundFrame.StreamDelta(
        runtime = AppServerRuntimeScope(agentId, conversationId),
        eventSeq = 1,
        emittedAt = "2026-06-27T00:00:00Z",
        idempotencyKey = "evt-1",
        delta = buildJsonObject {
            put("run_id", JsonPrimitive(runId))
        },
    )

/** Shorthand for the (request_id, tool_call_id) identity (lgns8.22.4.1.3). */
private fun controlRef(requestId: String, toolCallId: String? = null) =
    InboundControlRequestRegistry.RequestRef(requestId, toolCallId)
