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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-lgns8.22.4.1.1 — recovery control frames must survive the
 * no-subscriber window.
 *
 * `ReconnectCoordinator.reconnectRuntime()` issues `sync(recoverApprovals = true)`
 * AFTER the disconnected lease unsubscribed and BEFORE its successor subscribes.
 * The replayed control / external-tool requests therefore arrive with zero
 * subscribers attached. Dropping them (the pre-fix behaviour) leaves the request
 * unanswered and the server turn blocked until the idle watchdog — or forever,
 * if it is parked on a user-input gate.
 */
class RuntimeEventFanoutPendingControlTest {

    @Test
    fun recoveryControlFrameWithNoSubscriberIsBufferedForTheSuccessorLease() = runTest {
        val fanout = RuntimeEventFanout()

        // Recovery replay lands while no lease is subscribed.
        fanout.route(externalToolRequest("ext-recover-1", "tc-1"))
        assertEquals(0, fanout.subscriberCount())
        assertEquals(1, fanout.pendingControlFrameCount())

        // The successor lease subscribes and MUST see the replayed request.
        val (_, events) = fanout.subscribe(agentId, conversationId)
        assertEquals(0, fanout.pendingControlFrameCount(), "buffered frame is handed off, not retained")

        events.test {
            val received = awaitItem()
            val frame = assertIs<AppServerInboundFrame.ExternalToolCallRequest>(received.frame)
            assertEquals("ext-recover-1", frame.requestId)
            assertEquals("tc-1", frame.toolCallId)
            cancelAndIgnoreRemainingEvents()
        }

        // The handoff registered it exactly once and marked it dispatched, so it
        // stays claimable by the successor lease but is not re-registered.
        val entry = fanout.inboundControlRegistry().lookup(controlRef("ext-recover-1", "tc-1"), 0L)
        assertEquals(InboundControlRequestRegistry.State.Dispatched, entry?.state)
    }

    @Test
    fun bufferedRecoveryFrameIsScopedToItsOwnRuntime() = runTest {
        val fanout = RuntimeEventFanout()
        fanout.route(externalToolRequest("ext-recover-2", "tc-2"))

        // A subscriber for a DIFFERENT runtime must not steal the frame.
        val (_, otherEvents) = fanout.subscribe(AgentId("agent-other"), ConversationId("conv-other"))
        assertEquals(1, fanout.pendingControlFrameCount())
        otherEvents.test { expectNoEvents() }

        val (_, events) = fanout.subscribe(agentId, conversationId)
        assertEquals(0, fanout.pendingControlFrameCount())
        events.test {
            assertEquals("ext-recover-2", awaitItem().frame.requestId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun pendingControlBufferIsBoundedAndDropsOldestFirst() = runTest {
        val fanout = RuntimeEventFanout()
        val overflow = RuntimeEventFanout.MAX_PENDING_CONTROL_FRAMES + 8
        repeat(overflow) { index ->
            fanout.route(externalToolRequest("ext-$index", "tc-$index"))
        }
        assertEquals(
            RuntimeEventFanout.MAX_PENDING_CONTROL_FRAMES,
            fanout.pendingControlFrameCount(),
            "an unsubscribed runtime must not grow the fanout without bound",
        )

        val (_, events) = fanout.subscribe(agentId, conversationId)
        events.test {
            // Oldest-first eviction: the survivors are the newest frames.
            val firstSurvivor = awaitItem().frame.requestId
            assertEquals("ext-8", firstSurvivor)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun duplicateRecoveryReplayIsBufferedOnlyOnce() = runTest {
        val fanout = RuntimeEventFanout()
        fanout.route(externalToolRequest("ext-dup", "tc-dup"))
        fanout.route(externalToolRequest("ext-dup", "tc-dup"))
        assertEquals(1, fanout.pendingControlFrameCount())

        // Same request_id with a DIFFERENT tool_call_id is a distinct identity
        // (lgns8.22.4.1.3) and must be retained separately.
        fanout.route(externalToolRequest("ext-dup", "tc-other"))
        assertEquals(2, fanout.pendingControlFrameCount())
    }

    @Test
    fun frameFromAFailedGenerationIsDroppedAtFlushInsteadOfDelivered() = runTest {
        val registry = InboundControlRequestRegistry()
        val fanout = RuntimeEventFanout(
            inboundControlRegistry = registry,
            connectionGenerationProvider = { 1L },
        )
        fanout.route(externalToolRequest("ext-stale", "tc-stale", generation = 1L))
        assertEquals(1, fanout.pendingControlFrameCount())

        // The connection that produced the buffered frame dies before anyone subscribes.
        registry.failGeneration(1L)

        val (_, events) = fanout.subscribe(agentId, conversationId)
        assertEquals(0, fanout.pendingControlFrameCount())
        events.test { expectNoEvents() }
        assertTrue(registry.pendingCount() == 0)
    }

    private companion object {
        val agentId = AgentId("agent-1")
        val conversationId = ConversationId("conv-1")
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")

        fun externalToolRequest(
            requestId: String,
            toolCallId: String,
            generation: Long? = null,
        ): AppServerReceivedFrame = AppServerReceivedFrame(
            channel = AppServerChannel.Control,
            raw = buildJsonObject { put("type", "external_tool_call_request") },
            frame = AppServerInboundFrame.ExternalToolCallRequest(
                requestId = requestId,
                runtime = runtime,
                toolCallId = toolCallId,
                toolName = "Bash",
                input = buildJsonObject { put("command", "ls") },
            ),
            connectionGeneration = generation,
        )
    }
}

/** Shorthand for the (request_id, tool_call_id) identity (lgns8.22.4.1.3). */
private fun controlRef(requestId: String, toolCallId: String? = null) =
    InboundControlRequestRegistry.RequestRef(requestId, toolCallId)
