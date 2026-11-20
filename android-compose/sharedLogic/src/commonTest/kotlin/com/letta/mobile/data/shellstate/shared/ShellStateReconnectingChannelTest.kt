package com.letta.mobile.data.shellstate.shared

import com.letta.mobile.data.controller.reconnect.AppServerClientGeneration
import com.letta.mobile.data.controller.reconnect.FullJitterBackoff
import com.letta.mobile.data.controller.reconnect.ReconnectingAppServerClient
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.shellstate.ShellStateActor
import com.letta.mobile.data.shellstate.ShellStateEntry
import com.letta.mobile.data.shellstate.ShellStateSnapshot
import com.letta.mobile.data.shellstate.wire.ShellStateWire
import com.letta.mobile.data.transport.api.AppServerClientChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConnectionState
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive

/**
 * The production chain: [ReconnectingAppServerClient] (which numbers its connections), [AppServerClientChannel]
 * and [channelShellStateLink]. The reconnecting owner runs unconfined, so a drop and a redial happen back to back
 * before the shell state's collector (on a standard dispatcher) gets to run: it never sees a disconnected value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellStateReconnectingChannelTest {
    @Test
    fun aDropAndRedialQueuedBeforeTheCollectorResumesYieldsANewIdentityAndAFreshRead() = runTest(StandardTestDispatcher()) {
        val host = ScriptedHost()
        val firstRead = host.expectRead()
        val freshRead = host.expectRead()
        val owner = CoroutineScope(Job() + UnconfinedTestDispatcher(testScheduler))
        val client = ReconnectingAppServerClient(
            connect = { host.nextGeneration() }, backoff = FullJitterBackoff(baseDelayMs = 1, maxDelayMs = 1), sleep = { },
        )
        client.start(owner)
        host.generations.single().ready()
        val channel = AppServerClientChannel(client, backgroundScope, setOf(ShellStateWire.CAPABILITY))
        val link = channelShellStateLink(channel)
        val shared = SharedShellState(link)
        val job = launch { shared.run() }
        runCurrent()
        val before = link.currentConnection
        assertEquals(1, host.reads, "the first connection's read is in flight")

        host.generations.first().fail("socket lost")
        host.generations[1].ready()
        runCurrent()

        assertNotEquals(before, link.currentConnection, "a redial is a different connection identity")
        assertEquals(2, host.reads, "the new connection started a fresh read")
        freshRead.complete(host.document(2))
        runCurrent()
        firstRead.complete(host.document(100))
        runCurrent()
        assertEquals(2L, shared.state.value.snapshot?.documentRevision, "the earlier connection's late reply is dropped")
        assertTrue(shared.state.value.live)
        job.cancel()
        owner.coroutineContext[Job]?.cancel()
    }

    /** Hands out connection generations whose clients answer reads when the test says so. */
    private class ScriptedHost {
        val generations = mutableListOf<Generation>()
        private val scripted = ArrayDeque<CompletableDeferred<AppServerInboundFrame.AdminRpcResponse>>()
        var reads = 0
            private set

        fun nextGeneration(): AppServerClientGeneration = Generation(this).also { generations += it }.handle()

        fun expectRead() = CompletableDeferred<AppServerInboundFrame.AdminRpcResponse>().also { scripted.addLast(it) }

        fun document(revision: Long) = AppServerInboundFrame.AdminRpcResponse(
            requestId = "r", success = true,
            result = LettaJson.persisted.encodeToJsonElement(
                ShellStateSnapshot.serializer(),
                ShellStateSnapshot(
                    1, revision,
                    mapOf("a" to ShellStateEntry(JsonPrimitive("v$revision"), revision, ShellStateActor.User("endpoint-b"), 1)),
                ),
            ),
        )

        suspend fun read(): AppServerInboundFrame.AdminRpcResponse {
            reads += 1
            return scripted.removeFirst().await()
        }
    }

    private class Generation(host: ScriptedHost) {
        private val connectionState = MutableStateFlow<AppServerConnectionState>(AppServerConnectionState.Connecting)
        private val client = ScriptedClient(host)

        fun handle() = AppServerClientGeneration(client, connectionState, close = { })

        fun ready() {
            connectionState.value = AppServerConnectionState.Ready
        }

        fun fail(reason: String) {
            connectionState.value = AppServerConnectionState.Failed(terminal = false, reason = reason)
        }
    }

    private class ScriptedClient(private val host: ScriptedHost) : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = emptyFlow()

        override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = host.read()

        override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId, success = true,
                runtime = AppServerRuntimeScope(agentId = command.agentId ?: "agent", conversationId = command.conversationId ?: "conv"),
            )

        override suspend fun input(command: AppServerCommand.Input) = Unit

        override suspend fun sync(command: AppServerCommand.Sync) =
            AppServerInboundFrame.SyncResponse(requestId = command.requestId ?: "sync", runtime = command.runtime, success = true)

        override suspend fun abort(command: AppServerCommand.AbortMessage) = AppServerInboundFrame.AbortMessageResponse(
            requestId = command.requestId ?: "abort", runtime = command.runtime, aborted = true, success = true,
        )

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }
}
