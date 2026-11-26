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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
     * A generation that reached Ready and then ends terminally (policy or auth close) goes straight
     * to give-up with no disconnect callback, so give-up must stop that generation's restore too.
     */
    @Test
    fun aTerminalCloseCancelsTheRestoreInFlightForTheDeadGeneration() = runTest {
        val server = ListenModeAppServer()
        val (client, generations) = runtimeLane(server)

        client.start(backgroundScope)
        client.awaitReady()
        runCurrent()
        assertEquals(1, server.droppedChannelCommands.size, "the restore is in flight on the unanswered channels_list")
        generations[0].connectionState.value =
            AppServerConnectionState.Failed(terminal = true, reason = "policy violation")
        runCurrent()
        assertTrue(client.state.value is ReconnectingClientState.GaveUp, "${client.state.value}")
        advanceTimeBy(RESTORE_SETTLES_WITHIN_MS)
        runCurrent()

        assertEquals(1, server.droppedChannelCommands.size, "no channel command after give-up")
        assertTrue(
            lines.none { it.startsWith("error: channel restore failed") },
            "a restore of a dead generation must be cancelled, not left to time out: $lines",
        )
        assertTrue(
            recorder.snapshot().none { it.name == "list_channels_failed" },
            "give-up must cancel the enumeration before its bound reports a failure",
        )
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
            override fun lastKnownEnabled(): List<ChannelAccountRef> = error("cache unreadable")

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

    /**
     * Cancellation is cooperative: a restore already inside its last, non-suspending step (the
     * account-cache write) when its generation fails cannot be stopped there. The next
     * generation's restore must wait it out instead of reading and writing the same cache
     * alongside it. Real threads, because the stuck step blocks a thread.
     */
    @Test
    fun aRestoreStuckInItsFinalCacheWriteIsWaitedOutByTheNextGenerationsRestore() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val firstWriteEntered = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        val secondRestoreStarted = CountDownLatch(1)
        val cache = object : ChannelAccountCache {
            private val delegate = InMemoryChannelAccountCache()
            private var writes = 0

            override fun lastKnownEnabled(): List<ChannelAccountRef> {
                if (events.contains("write 1 start")) {
                    events += "read 2"
                    secondRestoreStarted.countDown()
                }
                return delegate.lastKnownEnabled()
            }

            override fun record(accounts: List<ChannelAccountRef>) {
                val write = ++writes
                events += "write $write start"
                if (write == 1) {
                    firstWriteEntered.countDown()
                    releaseFirstWrite.await(STUCK_WRITE_BOUND_S, TimeUnit.SECONDS)
                }
                delegate.record(accounts)
                events += "write $write end"
            }
        }
        val nodeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val servers = listOf("matrix", "slack").map {
                ListenModeAppServer(channelServiceRunning = true, channels = listOf(it))
            }
            val generations = servers.map(::Generation)
            var next = 0
            val client = ReconnectingAppServerClient(
                connect = {
                    val generation = generations[next++]
                    AppServerClientGeneration(
                        client = DefaultAppServerClient(generation.server.transport, parentScope = nodeScope),
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
                    restoreScope = nodeScope,
                ),
                sleep = {},
            )

            client.start(nodeScope)
            assertTrue(firstWriteEntered.await(STUCK_WRITE_BOUND_S, TimeUnit.SECONDS), "first restore reached its cache write")
            generations[0].connectionState.value =
                AppServerConnectionState.Failed(terminal = false, reason = "socket closed")
            // Give a replacement restore the chance to start while the first is still writing.
            val overlapped = secondRestoreStarted.await(OVERLAP_WINDOW_S, TimeUnit.SECONDS)
            releaseFirstWrite.countDown()
            withTimeout(STUCK_WRITE_BOUND_S.seconds) {
                client.state.first { it is ReconnectingClientState.Ready && it.connectionEpoch == 2L }
            }
            withTimeout(STUCK_WRITE_BOUND_S.seconds) {
                while (!events.contains("write 2 end")) kotlinx.coroutines.delay(10)
            }

            assertTrue(!overlapped, "the next restore started while the previous one was still writing: $events")
            assertEquals(listOf("write 1 start", "write 1 end", "read 2", "write 2 start", "write 2 end"), events.toList())
            assertTrue("slack/slack-bot" in servers[1].startedAccounts, "${servers[1].startedAccounts}")
        } finally {
            releaseFirstWrite.countDown()
            nodeScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private companion object {
        const val READY_WITHIN_MS = 5_000L

        /** Past the enumeration bound, well short of the wrapper's request timeout. */
        const val RESTORE_SETTLES_WITHIN_MS = 60_000L

        /** Upper bound on any real-time wait in the stuck-write test; never reached when it passes. */
        const val STUCK_WRITE_BOUND_S = 20L

        /** How long the stuck-write test lets an overlapping restore show itself. */
        const val OVERLAP_WINDOW_S = 3L
    }
}
