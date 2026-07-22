package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerConnectionState
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ReconnectingAppServerClientTest {
    private val runtime = AppServerRuntimeScope(agentId = "agent-1", conversationId = "conv-1")

    private class FakeGenerationClient : AppServerClient {
        val emitted = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = 16)
        override val events: Flow<AppServerReceivedFrame> = emitted
        val syncCommands = mutableListOf<AppServerCommand.Sync>()

        override suspend fun runtimeStart(
            command: AppServerCommand.RuntimeStart,
        ): AppServerInboundFrame.RuntimeStartResponse =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = command.agentId ?: "agent",
                    conversationId = command.conversationId ?: "conv",
                ),
            )

        override suspend fun input(command: AppServerCommand.Input) = Unit

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse {
            syncCommands += command
            return AppServerInboundFrame.SyncResponse(
                requestId = command.requestId ?: "sync",
                runtime = command.runtime,
                success = true,
            )
        }

        override suspend fun abort(
            command: AppServerCommand.AbortMessage,
        ): AppServerInboundFrame.AbortMessageResponse =
            AppServerInboundFrame.AbortMessageResponse(
                requestId = command.requestId ?: "abort",
                runtime = command.runtime,
                aborted = true,
                success = true,
            )

        override suspend fun adminRpc(
            command: AppServerCommand.AdminRpc,
        ): AppServerInboundFrame.AdminRpcResponse =
            AppServerInboundFrame.AdminRpcResponse(requestId = command.requestId, success = true)

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit
    }

    private class FakeGeneration {
        val connectionState = MutableStateFlow<AppServerConnectionState>(AppServerConnectionState.Connecting)
        val client = FakeGenerationClient()
        var closedReason: String? = null
        var closed = false

        fun handle() = AppServerClientGeneration(
            client = client,
            connectionState = connectionState,
            close = { reason ->
                closed = true
                closedReason = reason
            },
        )

        fun ready() {
            connectionState.value = AppServerConnectionState.Ready
        }

        fun fail(reason: String, terminal: Boolean = false) {
            connectionState.value = AppServerConnectionState.Failed(terminal = terminal, reason = reason)
        }
    }

    private class RecordingListener : ReconnectingClientListener {
        val disconnects = mutableListOf<String?>()
        val recoveries = mutableListOf<AppServerClient>()
        val gaveUp = mutableListOf<String?>()
        var failRecoveryOnce = false

        override suspend fun onDisconnected(reason: String?) {
            disconnects += reason
        }

        override suspend fun onRecovered(client: AppServerClient) {
            if (failRecoveryOnce) {
                failRecoveryOnce = false
                error("recovery boom")
            }
            recoveries += client
        }

        override suspend fun onGaveUp(reason: String?) {
            gaveUp += reason
        }
    }

    private fun backoff(maxAttempts: Int = 5) =
        FullJitterBackoff(baseDelayMs = 100, maxDelayMs = 1_000, maxAttempts = maxAttempts, random = Random(1))

    @Test
    fun failsFastBeforeFirstGenerationIsReady() = runTest {
        val gen = FakeGeneration()
        val client = ReconnectingAppServerClient(connect = { gen.handle() }, backoff = backoff())
        val job = client.start(backgroundScope)
        runCurrent()

        assertFailsWith<AppServerNotConnectedException> {
            client.sync(AppServerCommand.Sync(runtime = runtime, requestId = "s1"))
        }
        job.cancel()
    }

    @Test
    fun becomesReadyAfterRecoveryAndDelegatesCalls() = runTest {
        val gen = FakeGeneration()
        val listener = RecordingListener()
        val client = ReconnectingAppServerClient(connect = { gen.handle() }, listener = listener, backoff = backoff())
        client.start(backgroundScope)
        runCurrent()

        gen.ready()
        runCurrent()

        assertEquals(ReconnectingClientState.Ready, client.state.value)
        assertEquals(1, listener.recoveries.size)
        val response = client.sync(AppServerCommand.Sync(runtime = runtime, requestId = "s1"))
        assertTrue(response.success)
        assertEquals(1, gen.client.syncCommands.size)
    }

    @Test
    fun generationFailureInvalidatesThenRecoversOnTheNextGeneration() = runTest {
        val generations = mutableListOf<FakeGeneration>()
        val listener = RecordingListener()
        val sleeps = mutableListOf<Long>()
        val client = ReconnectingAppServerClient(
            connect = { FakeGeneration().also { generations += it }.handle() },
            listener = listener,
            backoff = backoff(),
            sleep = { sleeps += it },
        )
        client.start(backgroundScope)
        runCurrent()
        generations.first().ready()
        runCurrent()
        assertEquals(ReconnectingClientState.Ready, client.state.value)

        generations.first().fail("socket lost")
        runCurrent()
        assertEquals<List<String?>>(listOf("socket lost"), listener.disconnects)
        assertTrue(generations.first().closed)
        assertEquals(1, sleeps.size, "reconnect must back off before redialing")

        assertEquals(2, generations.size)
        assertFailsWith<AppServerNotConnectedException> {
            client.sync(AppServerCommand.Sync(runtime = runtime, requestId = "s2"))
        }
        generations[1].ready()
        runCurrent()

        assertEquals(ReconnectingClientState.Ready, client.state.value)
        assertEquals(2, listener.recoveries.size)
        client.sync(AppServerCommand.Sync(runtime = runtime, requestId = "s3"))
        assertEquals(1, generations[1].client.syncCommands.size)
    }

    @Test
    fun terminalFailureGivesUpWithoutRetrying() = runTest {
        val generations = mutableListOf<FakeGeneration>()
        val listener = RecordingListener()
        val client = ReconnectingAppServerClient(
            connect = { FakeGeneration().also { generations += it }.handle() },
            listener = listener,
            backoff = backoff(),
            sleep = { },
        )
        client.start(backgroundScope)
        runCurrent()

        generations.single().fail("policy violation", terminal = true)
        runCurrent()

        assertIs<ReconnectingClientState.GaveUp>(client.state.value)
        assertEquals(1, generations.size)
        assertEquals<List<String?>>(listOf("policy violation"), listener.gaveUp)
    }

    @Test
    fun exhaustedAttemptBudgetGivesUp() = runTest {
        var attempts = 0
        val listener = RecordingListener()
        val sleeps = mutableListOf<Long>()
        val client = ReconnectingAppServerClient(
            connect = {
                attempts += 1
                error("dial refused")
            },
            listener = listener,
            backoff = backoff(maxAttempts = 3),
            sleep = { sleeps += it },
        )
        client.start(backgroundScope)
        runCurrent()

        assertIs<ReconnectingClientState.GaveUp>(client.state.value)
        // Initial try + 3 backed-off retries consume the budget.
        assertEquals(4, attempts)
        assertEquals(3, sleeps.size)
        assertEquals(1, listener.gaveUp.size)
    }

    @Test
    fun recoveryFailureClosesTheGenerationAndRetries() = runTest {
        val generations = mutableListOf<FakeGeneration>()
        val listener = RecordingListener().apply { failRecoveryOnce = true }
        val client = ReconnectingAppServerClient(
            connect = { FakeGeneration().also { generations += it }.handle() },
            listener = listener,
            backoff = backoff(),
            sleep = { },
        )
        client.start(backgroundScope)
        runCurrent()
        generations[0].ready()
        runCurrent()

        assertTrue(generations[0].closed)
        assertEquals(2, generations.size)
        generations[1].ready()
        runCurrent()

        assertEquals(ReconnectingClientState.Ready, client.state.value)
        assertEquals(1, listener.recoveries.size)
    }

    @Test
    fun eventsFromLaterGenerationsReachCollectorsStartedEarlier() = runTest {
        val generations = mutableListOf<FakeGeneration>()
        val client = ReconnectingAppServerClient(
            connect = { FakeGeneration().also { generations += it }.handle() },
            backoff = backoff(),
            sleep = { },
        )
        client.start(backgroundScope)

        val received = mutableListOf<AppServerReceivedFrame>()
        backgroundScope.launch { client.events.collect { received += it } }
        runCurrent()
        generations[0].ready()
        runCurrent()

        generations[0].fail("socket lost")
        runCurrent()
        generations[1].ready()
        runCurrent()

        val raw = kotlinx.serialization.json.buildJsonObject { }
        val frame = AppServerReceivedFrame(
            channel = AppServerChannel.Stream,
            frame = AppServerInboundFrame.Unknown(type = "test_frame", raw = raw),
            raw = raw,
        )
        generations[1].client.emitted.tryEmit(frame)
        runCurrent()

        assertEquals(listOf(frame), received)
    }

    @Test
    fun callsAreAdmittedDuringRecovery() = runTest {
        val gen = FakeGeneration()
        lateinit var client: ReconnectingAppServerClient
        var recoverySyncSucceeded = false
        client = ReconnectingAppServerClient(
            connect = { gen.handle() },
            listener = object : ReconnectingClientListener {
                override suspend fun onRecovered(generationClient: AppServerClient) {
                    // The production recovery flow drives the controller, whose
                    // calls come back through the facade — they must be admitted.
                    client.sync(AppServerCommand.Sync(runtime = runtime, requestId = "recovery-sync"))
                    recoverySyncSucceeded = true
                }
            },
            backoff = backoff(),
        )
        client.start(backgroundScope)
        runCurrent()
        gen.ready()
        runCurrent()

        assertTrue(recoverySyncSucceeded)
        assertEquals(ReconnectingClientState.Ready, client.state.value)
    }
}
