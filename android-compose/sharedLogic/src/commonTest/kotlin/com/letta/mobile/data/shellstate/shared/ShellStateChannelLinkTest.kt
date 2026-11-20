package com.letta.mobile.data.shellstate.shared

import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateEntry
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

/** The real [channelShellStateLink] over a channel transport: a reconnect to the same endpoint is a new connection. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellStateChannelLinkTest {
    @Test
    fun aDropAndReconnectToTheSameEndpointQueuedBeforeTheCollectorResumesStartsAFreshRead() = runTest(StandardTestDispatcher()) {
        val transport = ScriptedChannel()
        val firstRead = transport.expectRead()
        val freshRead = transport.expectRead()
        val shared = SharedShellState(channelShellStateLink(transport))
        val job = launch { shared.run() }
        runCurrent()
        assertEquals(1, transport.reads, "the first connection's read is in flight")
        // Down and up again with the very same session id (as every redial of one endpoint has), both queued
        // before the collector runs: it never sees the disconnect, only a different connection.
        transport.state.value = ChannelTransportState.Disconnected(1006, "lost", willReconnect = true)
        transport.state.value = connected(epoch = 2)
        runCurrent()
        assertEquals(2, transport.reads, "the new connection started a fresh read")
        freshRead.complete(response(2))
        runCurrent()
        firstRead.complete(response(100))
        runCurrent()
        assertEquals(2L, shared.state.value.snapshot?.documentRevision, "the earlier connection's late reply is dropped")
        assertTrue(shared.state.value.live)
        job.cancel()
    }

    private fun connected(epoch: Long) = ChannelTransportState.Connected(
        serverId = "iroh-app-server", sessionId = SAME_SESSION, deviceId = "device", connectionEpoch = epoch,
    )

    private fun response(revision: Long) = AppServerInboundFrame.AdminRpcResponse(
        requestId = "r", success = true,
        result = LettaJson.persisted.encodeToJsonElement(
            ShellStateSnapshot.serializer(),
            ShellStateSnapshot(
                1, revision, mapOf("a" to ShellStateEntry(JsonPrimitive("v$revision"), revision, ShellStateActor.User("endpoint-b"), 1)),
            ),
        ),
    )

    /** A channel transport over one endpoint whose reads are answered when the test says so. */
    private inner class ScriptedChannel : NoOpChannelTransport() {
        override val state: MutableStateFlow<ChannelTransportState> = MutableStateFlow(connected(epoch = 1))
        override val hostCapabilities: Set<String> = setOf(ShellStateWire.CAPABILITY)
        private val scripted = ArrayDeque<CompletableDeferred<AppServerInboundFrame.AdminRpcResponse>>()
        var reads = 0
            private set

        fun expectRead() = CompletableDeferred<AppServerInboundFrame.AdminRpcResponse>().also { scripted.addLast(it) }

        override suspend fun adminRpc(method: String, path: String, body: String?): AppServerInboundFrame.AdminRpcResponse {
            reads += 1
            return scripted.removeFirst().await()
        }
    }

    private companion object {
        const val SAME_SESSION = "ticket-hash"
    }
}
