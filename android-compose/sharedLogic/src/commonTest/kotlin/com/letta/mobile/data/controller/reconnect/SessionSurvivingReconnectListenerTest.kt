package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.controller.fanout.RuntimeSessionLink
import com.letta.mobile.data.controller.fanout.isRuntimeStreamDetached
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConnectionState
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-by4vm.9: the reconnect owner ([ReconnectingAppServerClient]) driving a router that
 * outlives the socket. A dropped generation interrupts the session without closing the turn's
 * subscription; the next generation reattaches the watched runtime, restores the link, and its
 * frames reach the same subscriber.
 */
class SessionSurvivingReconnectListenerTest {

    @Test
    fun aDroppedSocketInterruptsAndTheNextGenerationFeedsTheSameSubscriber() = runTest {
        val harness = harness()
        harness.generations.single().ready()
        runCurrent()
        // A turn is streaming when the socket drops.
        val (_, frames) = harness.router.subscribe(AgentId(AGENT), ConversationId(CONVERSATION))
        val received = mutableListOf<AppServerReceivedFrame>()
        backgroundScope.launch { frames.collect { received += it } }
        runCurrent()

        harness.generations.single().fail("wifi blip")
        runCurrent()

        assertEquals(RuntimeSessionLink.Interrupted(epoch = 0, reason = "wifi blip"), harness.router.sessionLink.value)
        assertEquals(1, harness.router.subscriberCount(), "the turn's subscription survives the drop")
        assertEquals(1, harness.sessionLosses, "generation-local state is dropped once")

        harness.generations[1].ready()
        runCurrent()

        assertEquals(listOf(RUNTIME), harness.reattached, "the watched runtime is reattached on the new socket")
        assertEquals(RuntimeSessionLink.Linked(epoch = 1), harness.router.sessionLink.value)
        harness.generations[1].client.emitted.emit(delta())
        runCurrent()
        assertEquals(1, received.size, "the successor socket's frame reaches the surviving subscriber")
    }

    @Test
    fun aRefusedReattachKeepsTheSessionInterruptedAndRetries() = runTest {
        val harness = harness(refuseReattachOnce = true)
        harness.generations.single().ready()
        runCurrent()
        harness.router.subscribe(AgentId(AGENT), ConversationId(CONVERSATION))
        harness.generations.single().fail("socket closed")
        runCurrent()

        harness.generations[1].ready()
        runCurrent()

        assertIs<RuntimeSessionLink.Interrupted>(harness.router.sessionLink.value)
        assertTrue(harness.generations[1].closed, "a generation whose reattach failed is closed")
        harness.generations[2].ready()
        runCurrent()
        assertEquals(RuntimeSessionLink.Linked(epoch = 1), harness.router.sessionLink.value)
    }

    @Test
    fun givingUpEndsEveryWaitingSubscriptionAtOnce() = runTest {
        val harness = harness()
        harness.generations.single().ready()
        runCurrent()
        val (_, frames) = harness.router.subscribe(AgentId(AGENT), ConversationId(CONVERSATION))
        var failure: Throwable? = null
        backgroundScope.launch {
            try {
                frames.collect { }
            } catch (e: CancellationException) {
                failure = e
            }
        }
        runCurrent()

        harness.generations.single().fail("policy violation", terminal = true)
        runCurrent()

        assertIs<ReconnectingClientState.GaveUp>(harness.client.state.value)
        assertEquals(0, harness.router.subscriberCount())
        assertTrue(failure?.isRuntimeStreamDetached() == true, "the turn sees a permanent detach, not an interruption")
    }

    private class Harness(
        val client: ReconnectingAppServerClient,
        val router: AppServerRuntimeEventRouter,
        val generations: List<FakeGeneration>,
        val reattached: List<AppServerRuntimeScope>,
    ) {
        var sessionLosses = 0
    }

    private fun TestScope.harness(refuseReattachOnce: Boolean = false): Harness {
        val generations = mutableListOf<FakeGeneration>()
        val reattached = mutableListOf<AppServerRuntimeScope>()
        val router = AppServerRuntimeEventRouter()
        var refuse = refuseReattachOnce
        lateinit var harness: Harness
        val client = ReconnectingAppServerClient(
            connect = { FakeGeneration().also { generations += it }.handle() },
            listener = SessionSurvivingReconnectListener(
                router = router,
                onSessionLost = { harness.sessionLosses += 1 },
                reattach = { generationClient, runtimes ->
                    generationClient.reattachRuntimes(runtimes) { runtime ->
                        AppServerCommand.RuntimeStart(
                            requestId = "reattach",
                            agentId = runtime.agentId,
                            conversationId = runtime.conversationId,
                        )
                    }
                    if (refuse && runtimes.isNotEmpty()) {
                        refuse = false
                        error("runtime_start refused")
                    }
                    reattached += runtimes
                },
            ),
            backoff = FullJitterBackoff(baseDelayMs = 100, maxDelayMs = 1_000),
            sleep = { },
            stampGenerations = false,
        )
        harness = Harness(client, router, generations, reattached)
        router.attach(backgroundScope, client.events)
        client.start(backgroundScope)
        runCurrent()
        return harness
    }

    private class FakeGeneration {
        val connectionState = MutableStateFlow<AppServerConnectionState>(AppServerConnectionState.Connecting)
        val client = FakeClient()
        var closed = false

        fun handle() = AppServerClientGeneration(client, connectionState) { closed = true }

        fun ready() {
            connectionState.value = AppServerConnectionState.Ready
        }

        fun fail(reason: String, terminal: Boolean = false) {
            connectionState.value = AppServerConnectionState.Failed(terminal = terminal, reason = reason)
        }
    }

    private class FakeClient : AppServerClient {
        val emitted = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)
        override val events: Flow<AppServerReceivedFrame> = emitted

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) =
            AppServerInboundFrame.RuntimeStartResponse(requestId = command.requestId, success = true, runtime = RUNTIME)

        override suspend fun input(command: AppServerCommand.Input) = Unit
        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse = error("sync unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
            error("abort unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
            error("adminRpc unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }

    private companion object {
        const val AGENT = "agent-1"
        const val CONVERSATION = "conv-1"
        val RUNTIME = AppServerRuntimeScope(AGENT, CONVERSATION)

        fun delta(): AppServerReceivedFrame {
            val frame = AppServerInboundFrame.StreamDelta(
                runtime = RUNTIME,
                eventSeq = 1,
                emittedAt = "2026-10-14T00:00:01Z",
                idempotencyKey = "evt-1",
                delta = buildJsonObject { put("message_type", "assistant_message") },
            )
            return AppServerReceivedFrame(
                channel = AppServerChannel.Stream,
                frame = frame,
                raw = buildJsonObject { put("type", "stream_delta") },
            )
        }
    }
}
