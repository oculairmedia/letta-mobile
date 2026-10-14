package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.SyncBudgetPolicy
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.controller.fanout.StreamIntegrityMonitor
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * letta-mobile-by4vm.50: an Android Iroh dial binds a [StreamIntegrityMonitor] with gap
 * detection only when the host advertised [StreamIntegrityMonitor.EVENT_SEQ_ORDERED_CAPABILITY]
 * in its auth_response. Older hosts keep today's behaviour: no monitor, so neither gap resyncs
 * nor cadence syncs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IrohDialerStreamIntegrityTest {

    private val runtime = AppServerRuntimeScope("agent-1", "conv-1")

    @Test
    fun aHostThatOrdersEventSeqGetsAGapResyncWithApprovalRecovery() = runTest {
        val wire = Wire(this)
        val job = wire.bind(setOf(IrohFrameCodec.FRAME_PART_CAPABILITY, StreamIntegrityMonitor.EVENT_SEQ_ORDERED_CAPABILITY))

        assertNotNull(job, "a capable host gets a monitor")
        wire.deliver(1L, 2L, 4L)
        runCurrent()

        assertEquals(1, wire.client.syncs.size, "one recovery sync for the lost frame 3")
        val sync = wire.client.syncs.single()
        assertEquals(runtime, sync.runtime)
        assertEquals(true, sync.recoverApprovals)
        assertTrue(sync.requestId.orEmpty().startsWith("iroh-sync-"))
        job.cancel()
    }

    @Test
    fun aContiguousSequenceFromACapableHostSendsNoRecoverySync() = runTest {
        val wire = Wire(this)
        val job = wire.bind(setOf(StreamIntegrityMonitor.EVENT_SEQ_ORDERED_CAPABILITY))

        wire.deliver(1L, 2L, 3L, 4L)
        runCurrent()

        assertEquals(emptyList(), wire.client.syncs)
        job?.cancel()
    }

    @Test
    fun aHostWithoutTheCapabilityGetsNoMonitor() = runTest {
        val wire = Wire(this)
        val job = wire.bind(setOf(IrohFrameCodec.FRAME_PART_CAPABILITY))

        assertNull(job, "no monitor is bound for an older host")
        wire.deliver(1L, 2L, 9L)
        wire.router.requestResync() // no monitor: a no-op
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(emptyList(), wire.client.syncs)
    }

    @Test
    fun aHostThatSentNoCapabilitiesGetsNoMonitor() = runTest {
        val wire = Wire(this)
        assertNull(wire.bind(null))
        wire.deliver(1L, 5L)
        runCurrent()
        assertEquals(emptyList(), wire.client.syncs)
    }

    private inner class Wire(private val test: TestScope) {
        val client = RecordingClient()
        val router = AppServerRuntimeEventRouter()
        private val dialer = IrohDialer(
            scope = test.backgroundScope,
            onConnectionLost = { _, _ -> },
            // A budget that says no proves a gap resync never waits on it.
            syncBudget = SyncBudgetPolicy { false },
        )

        fun bind(hostCapabilities: Set<String>?) =
            dialer.bindIrohStreamIntegrity(router, client, hostCapabilities, test.backgroundScope).also {
                router.attach(test.backgroundScope, client.inbound)
                router.subscribe(AgentId(runtime.agentId), ConversationId(runtime.conversationId))
            }

        fun deliver(vararg seqs: Long) {
            seqs.forEach { seq -> check(client.inbound.tryEmit(delta(seq))) }
        }
    }

    private fun delta(seq: Long) = AppServerReceivedFrame(
        channel = AppServerChannel.Stream,
        frame = AppServerInboundFrame.StreamDelta(
            runtime = runtime,
            eventSeq = seq,
            emittedAt = "t",
            idempotencyKey = "k-$seq",
            delta = buildJsonObject { },
        ),
        raw = JsonObject(emptyMap()),
    )

    private class RecordingClient : AppServerClient {
        val inbound = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 64)
        val syncs = mutableListOf<AppServerCommand.Sync>()

        override val events = inbound

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse {
            syncs += command
            return AppServerInboundFrame.SyncResponse(
                requestId = command.requestId.orEmpty(),
                runtime = command.runtime,
                success = true,
            )
        }

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = error("unused")
        override suspend fun input(command: AppServerCommand.Input) = error("unused")
        override suspend fun abort(command: AppServerCommand.AbortMessage) = error("unused")
        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = error("unused")
        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = error("unused")
    }
}
