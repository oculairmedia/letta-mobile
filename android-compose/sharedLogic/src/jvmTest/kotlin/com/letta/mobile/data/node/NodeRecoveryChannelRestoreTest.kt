package com.letta.mobile.data.node

import com.letta.mobile.data.controller.channels.ChannelAccountCache
import com.letta.mobile.data.controller.channels.ChannelAccountRef
import com.letta.mobile.data.controller.channels.InMemoryChannelAccountCache
import com.letta.mobile.data.controller.channels.ListenModeAppServer
import com.letta.mobile.data.controller.reconnect.AppServerClientGeneration
import com.letta.mobile.data.controller.reconnect.ReconnectingAppServerClient
import com.letta.mobile.data.controller.reconnect.ReconnectingClientState
import com.letta.mobile.data.transport.appserver.AppServerConnectionState
import com.letta.mobile.data.transport.appserver.DefaultAppServerClient
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The node's post-connect recovery against an App Server that never answers `channels_list`
 * (`letta server --listen`), driven by the real reconnect supervisor and request registry under
 * virtual time, with the wrapper's production request timeout.
 */
class NodeRecoveryChannelRestoreTest {
    private var telemetry: AutoCloseable? = null
    private val recorder = TelemetryRecorder()
    private val lines = mutableListOf<String>()
    private val log = object : NodeLog {
        override fun info(line: String) {
            lines += line
        }

        override fun error(line: String) {
            lines += "error: $line"
        }
    }

    @BeforeTest
    fun installTelemetry() {
        telemetry = Telemetry.installRecorder(recorder)
    }

    @AfterTest
    fun detachTelemetry() {
        telemetry?.close()
    }

    private class Generation(val server: ListenModeAppServer) {
        val connectionState = MutableStateFlow<AppServerConnectionState>(AppServerConnectionState.Ready)
    }

    /** A runtime lane over [servers], one per generation, with a management lane that is already Ready. */
    private fun TestScope.runtimeLane(
        vararg servers: ListenModeAppServer,
        cache: ChannelAccountCache = InMemoryChannelAccountCache(),
        restoreScope: CoroutineScope = backgroundScope,
    ): Pair<ReconnectingAppServerClient, List<Generation>> {
        val generations = servers.map(::Generation)
        var next = 0
        val client = ReconnectingAppServerClient(
            connect = {
                val generation = generations[next++]
                AppServerClientGeneration(
                    client = DefaultAppServerClient(
                        generation.server.transport,
                        requestTimeoutMs = ListenModeAppServer.WRAPPER_REQUEST_TIMEOUT_MS,
                        parentScope = backgroundScope,
                    ),
                    connectionState = generation.connectionState,
                    close = {},
                )
            },
            listener = NodeRecoveryListener(
                log = log,
                controller = { null },
                coordinator = { null },
                adminState = MutableStateFlow(ReconnectingClientState.Ready(connectionEpoch = 1)),
                channelAccountCache = cache,
                restoreScope = restoreScope,
            ),
            sleep = {},
        )
        return client to generations
    }

    private suspend fun ReconnectingAppServerClient.awaitReady(epoch: Long = 1) =
        state.first { it is ReconnectingClientState.Ready && it.connectionEpoch == epoch }

    @Test
    fun anUnansweredChannelEnumerationDoesNotDelayGenerationReady() = runTest {
        val (client, _) = runtimeLane(ListenModeAppServer())

        client.start(backgroundScope)
        client.awaitReady()

        assertTrue(
            currentTime <= READY_WITHIN_MS,
            "generation.ready must not wait on channel restore; it took ${currentTime}ms of virtual time",
        )
        assertTrue(
            recorder.snapshot().any { it.tag == "AppServerReconnect" && it.name == "generation.ready" },
            "the deploy gate greps for Telemetry/AppServerReconnect: generation.ready",
        )
    }

    @Test
    fun theRestoreStillRunsAfterReadinessAndItsFailureIsLoggedByName() = runTest {
        val server = ListenModeAppServer()
        val (client, _) = runtimeLane(server)

        client.start(backgroundScope)
        client.awaitReady()
        // The restore runs on the node scope (background work), which advanceUntilIdle skips.
        advanceTimeBy(RESTORE_SETTLES_WITHIN_MS)
        runCurrent()

        assertEquals(1, server.droppedChannelCommands.size, "the restore still issues channels_list")
        assertTrue(lines.any { it == "channels-host restore: channels=0 started=0 failed=1" }, "$lines")
        assertTrue(
            lines.any { it.startsWith("error: channel restore failed: -/- phase=LIST_CHANNELS reason=channels_list unanswered") },
            "$lines",
        )
    }

    /**
     * The fact that makes moving the restore off the readiness path safe: it is issued on the
     * generation's own client, which still reaches that generation's socket once the supervisor
     * is Ready, so `channel_start` re-wires ingress to the live socket exactly as before.
     */
    @Test
    fun aRestoreThatRunsAfterReadinessStillStartsAccountsOnTheGenerationSocket() = runTest {
        val server = ListenModeAppServer(channelServiceRunning = true, channels = listOf("matrix"))
        val (client, _) = runtimeLane(server)

        client.start(backgroundScope)
        client.awaitReady()
        // The restore runs on the node scope (background work), which advanceUntilIdle skips.
        advanceTimeBy(RESTORE_SETTLES_WITHIN_MS)
        runCurrent()

        assertEquals(listOf("matrix/matrix-bot"), server.startedAccounts)
        assertTrue(lines.any { it == "channels-host restore: channels=1 started=1 failed=0" }, "$lines")
    }

    /** A restore bound to a dead generation stops with it instead of running on into the next. */
    @Test
    fun aGenerationThatFailsCancelsItsRestoreAndTheNextGenerationRestoresAgain() = runTest {
        val first = ListenModeAppServer()
        val second = ListenModeAppServer(channelServiceRunning = true, channels = listOf("matrix"))
        val (client, generations) = runtimeLane(first, second)

        client.start(backgroundScope)
        client.awaitReady(epoch = 1)
        runCurrent()
        assertEquals(1, first.droppedChannelCommands.size)
        generations[0].connectionState.value =
            AppServerConnectionState.Failed(terminal = false, reason = "socket closed")
        client.awaitReady(epoch = 2)
        advanceTimeBy(RESTORE_SETTLES_WITHIN_MS)
        runCurrent()

        assertTrue(
            lines.none { it.startsWith("error: channel restore failed") },
            "the first generation's restore must be cancelled, not left to time out: $lines",
        )
        assertEquals(listOf("matrix/matrix-bot"), second.startedAccounts)
    }

    /**
     * A failure the coordinator does not turn into a restore result (here the account cache
     * throwing) stops that restore and is logged by name; it must not cancel the node scope, which
     * in production is a plain, non-supervisor scope that also runs the Iroh endpoint.
     */
    @Test
    fun anUnexpectedRestoreFailureIsLoggedAndLeavesTheNodeScopeRunning() = runTest {
        val nodeJob = Job(backgroundScope.coroutineContext[Job])
        val nodeScope = backgroundScope + nodeJob
        val brokenCache = object : ChannelAccountCache {
            override fun lastKnownEnabled(): List<ChannelAccountRef> = throw IllegalStateException("cache unreadable")

            override fun record(accounts: List<ChannelAccountRef>) = Unit
        }
        val (client, _) = runtimeLane(ListenModeAppServer(), cache = brokenCache, restoreScope = nodeScope)

        client.start(backgroundScope)
        client.awaitReady()
        advanceTimeBy(RESTORE_SETTLES_WITHIN_MS)
        runCurrent()

        assertTrue(nodeJob.isActive, "one restore failure must not cancel the node scope")
        assertTrue(
            lines.any { it == "error: channel restore failed: IllegalStateException: cache unreadable" },
            "$lines",
        )
        nodeJob.cancel()
    }

    private companion object {
        const val READY_WITHIN_MS = 5_000L

        /** Past the enumeration bound, well short of the wrapper's request timeout. */
        const val RESTORE_SETTLES_WITHIN_MS = 60_000L
    }
}
