package com.letta.mobile.data.controller.fanout

import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerLoopStatus
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
        val results = mutableListOf<AppServerInboundFrame>()
        
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
        
        fanout.route(streamDelta)
        
        job1.join()
        job2.join()

        assertEquals(2, results.size)
        assertEquals(streamDelta, results[0])
        assertEquals(streamDelta, results[1])
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

        val receivedA = mutableListOf<AppServerInboundFrame>()
        val receivedB = mutableListOf<AppServerInboundFrame>()

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
        fanout.route(eventForA)
        delay(50.milliseconds)

        // Send event to runtime B
        fanout.route(eventForB)
        delay(50.milliseconds)

        // Only subscriber A should have received eventForA
        assertEquals(1, receivedA.size)
        assertEquals(eventForA, receivedA[0])

        // Only subscriber B should have received eventForB
        assertEquals(1, receivedB.size)
        assertEquals(eventForB, receivedB[0])

        jobA.cancel()
        jobB.cancel()
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
            fanout.route(frameWithoutRuntime)

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
            fanout.route(eventForB)

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
            fanout.route(streamDelta)
            assertEquals(streamDelta, awaitItem())

            // UpdateLoopStatus
            val updateLoopStatus = AppServerInboundFrame.UpdateLoopStatus(
                runtime = runtime,
                eventSeq = 2,
                emittedAt = "2026-06-27T00:00:00Z",
                idempotencyKey = "evt-2",
                loopStatus = AppServerLoopStatus(status = "active"),
            )
            fanout.route(updateLoopStatus)
            assertEquals(updateLoopStatus, awaitItem())

            // UpdateDeviceStatus
            val updateDeviceStatus = AppServerInboundFrame.UpdateDeviceStatus(
                runtime = runtime,
                eventSeq = 3,
                emittedAt = "2026-06-27T00:00:00Z",
                idempotencyKey = "evt-3",
                deviceStatus = buildJsonObject {},
            )
            fanout.route(updateDeviceStatus)
            assertEquals(updateDeviceStatus, awaitItem())

            // UpdateQueue
            val updateQueue = AppServerInboundFrame.UpdateQueue(
                runtime = runtime,
                eventSeq = 4,
                emittedAt = "2026-06-27T00:00:00Z",
                idempotencyKey = "evt-4",
                queue = emptyList(),
            )
            fanout.route(updateQueue)
            assertEquals(updateQueue, awaitItem())

            // UpdateSubagentState
            val updateSubagentState = AppServerInboundFrame.UpdateSubagentState(
                runtime = runtime,
                eventSeq = 5,
                emittedAt = "2026-06-27T00:00:00Z",
                idempotencyKey = "evt-5",
                subagents = emptyList(),
            )
            fanout.route(updateSubagentState)
            assertEquals(updateSubagentState, awaitItem())

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
}

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
