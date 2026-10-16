package com.letta.mobile.data.runtime.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * letta-mobile-by4vm.110.4: the shared loopback App Server host with a fake launcher — the launch
 * contract, leases, readiness, and the crash supervision it took over from the desktop
 * (letta-mobile-bzvro.3 F03, .4 F04).
 */
class LocalAppServerHostTest {
    @Test
    fun launchRunsLoopbackServerWithCapabilityTokenAndLeaseCarriesIt() = hosted { f ->
        val lease = f.host.acquire()

        val start = f.starts.single()
        val tokenFile = "/data/backend/${LocalAppServerHost.TOKEN_FILE_NAME}"
        assertEquals(
            listOf(
                "node", "letta.js",
                "server", "--backend", "local",
                "--listen", "ws://127.0.0.1:0",
                "--ws-auth", "capability-token", "--ws-token-file", tokenFile,
            ),
            start.command,
        )
        assertEquals("1", start.environment["LETTA_LOCAL_BACKEND_EXPERIMENTAL"])
        assertEquals("/data/backend", start.environment["LETTA_LOCAL_BACKEND_DIR"])
        assertEquals("--max-old-space-size=512", start.environment["NODE_OPTIONS"], "the platform's environment is kept")
        assertEquals("/work", start.workingDirectory)
        assertEquals(tokenFile, start.tokenFile)
        assertEquals("ws://127.0.0.1:40001", lease.serverUrl)
        assertEquals(start.token, lease.bearerToken)
        assertEquals("token-1", lease.bearerToken)
    }

    @Test
    fun everyStartMintsAFreshToken() = hosted { f ->
        f.host.acquire()
        f.latest.crash()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf("token-1", "token-2"), f.starts.map { it.token })
    }

    @Test
    fun liveServerIsReusedAndDeadServerIsReplaced() = hosted { f ->
        assertEquals("ws://127.0.0.1:40001", f.host.acquire().serverUrl)
        assertEquals("ws://127.0.0.1:40001", f.host.acquire().serverUrl)
        assertEquals(1, f.launches.size)

        f.latest.alive = false
        assertEquals("ws://127.0.0.1:40002", f.host.acquire().serverUrl)
        assertEquals(2, f.launches.size)
    }

    @Test
    fun earlyExitIsReportedAndHostStaysRestartable() = hosted { f ->
        f.failNextStart = 23

        val failure = assertFailsWith<LocalAppServerStartException> { f.host.acquire() }
        assertTrue(failure.message.orEmpty().contains("exit=23"))
        assertEquals(23, failure.exitCode)
        assertEquals("ws://127.0.0.1:40002", f.host.acquire().serverUrl)
    }

    @Test
    fun leaseWaitsForReadyz() = hosted { f ->
        f.readyAfterPolls = 3

        f.host.acquire()

        assertEquals(3, f.readyzPolls)
    }

    @Test
    fun silentServerTimesOutAndIsKilled() = hosted { f ->
        f.silentNextStart = true

        val failure = assertFailsWith<LocalAppServerStartException> { f.host.acquire() }

        assertNull(failure.exitCode)
        assertFalse(f.latest.alive, "the half-started server is killed")
    }

    @Test
    fun lastLeaseCloseStopsTheServer() = hosted { f ->
        val first = f.host.acquire()
        val second = f.host.acquire()

        first.close()
        first.close()
        runCurrent()
        assertTrue(f.latest.alive, "another owner still holds it")

        second.close()
        runCurrent()
        assertFalse(f.latest.alive)
        assertFalse(f.host.health.value.active)
        assertFalse(f.host.health.value.needsForceRestart, "our own stop needs nothing from the user")
    }

    @Test
    fun crashesRestartWithGrowingBackoff() = hosted { f ->
        f.host.acquire()
        f.latest.crash()
        runCurrent()
        assertTrue(f.host.health.value.restartPending)

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, f.launches.size, "no restart before the 2 s backoff")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, f.launches.size)
        assertTrue(f.host.health.value.active)

        f.latest.crash()
        runCurrent()
        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(2, f.launches.size, "the second restart waits 4 s")
        advanceTimeBy(1)
        runCurrent()
        assertEquals(3, f.launches.size)
        assertEquals(2, f.host.health.value.consecutiveCrashes)
    }

    @Test
    fun fiveCrashesInsideAMinuteGiveUpUntilForceRestart() = hosted { f ->
        f.host.acquire()
        // Each restart dies at once: crashes at 0, 2, 6, 14 and 30 s, all inside one minute.
        listOf(2_000L, 4_000L, 8_000L, 16_000L).forEach { backoff ->
            f.latest.crash()
            runCurrent()
            advanceTimeBy(backoff)
            runCurrent()
        }
        assertEquals(5, f.launches.size)
        f.latest.err("FATAL ERROR: heap out of memory")
        f.latest.crash(exitCode = 9)
        advanceTimeBy(120_000)
        runCurrent()

        val health = f.host.health.value
        assertTrue(health.gaveUp)
        assertTrue(health.needsForceRestart)
        assertEquals(9, health.lastExitCode)
        assertEquals(listOf("FATAL ERROR: heap out of memory"), health.recentStderr)
        assertEquals(5, f.launches.size, "no restart after giving up")

        f.host.forceRestart()
        runCurrent()
        assertEquals(6, f.launches.size)
        assertFalse(f.host.health.value.gaveUp)
        assertEquals(0, f.host.health.value.consecutiveCrashes)
    }

    @Test
    fun aMinuteOfHealthyUptimeResetsTheBackoff() = hosted { f ->
        f.host.acquire()
        f.latest.crash()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        f.latest.crash()
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(3, f.launches.size)

        advanceTimeBy(60_000)
        f.latest.crash()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(4, f.launches.size, "back to the 2 s first-crash delay")
        assertEquals(1, f.host.health.value.consecutiveCrashes)
    }

    @Test
    fun cleanExitsNeverRestartButOfferRestart() = hosted { f ->
        f.host.acquire()
        f.latest.crash(exitCode = 0)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, f.launches.size)
        assertTrue(f.host.health.value.needsForceRestart, "an unasked-for clean exit offers Restart")
    }

    @Test
    fun nothingRestartsOnceNoLeaseHolderWantsTheServer() = hosted { f ->
        f.host.acquire().close()
        f.latest.crash()
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(1, f.launches.size)
    }

    @Test
    fun stderrTailIsKeptAndFlushedToTheLogOnACrash() = hosted { f ->
        f.host.acquire()
        (1..12).forEach { f.latest.err("err $it") }
        runCurrent()
        f.latest.crash()
        runCurrent()

        assertEquals((3..12).map { "err $it" }, f.host.health.value.recentStderr)
        assertTrue(f.log.any { it == "[supervisor] stderr: err 12" })
        assertTrue(f.log.any { it == "[stderr] err 1" })
    }

    @Test
    fun aCrashDuringSleepIsNotCountedAndWakeRestartsAtOnce() = hosted { f ->
        f.host.acquire()
        f.latest.crash()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(1, f.host.health.value.consecutiveCrashes)

        f.host.onSuspended()
        f.latest.crash()
        runCurrent()
        assertFalse(f.host.health.value.restartPending, "exits during sleep are not crashes")
        f.host.onResumed()
        runCurrent()

        assertEquals(3, f.launches.size, "wake restarted the dead server without backoff")
        assertEquals(0, f.host.health.value.consecutiveCrashes)
    }

    @Test
    fun supervisedRestartsAreAnnouncedForTheClientToReconnect() = hosted { f ->
        val urls = mutableListOf<String>()
        backgroundScope.launch { f.host.restarted.collect { urls += it } }
        runCurrent()
        f.host.acquire()
        f.latest.crash()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(listOf("ws://127.0.0.1:40002"), urls, "lease-driven starts are not announced, restarts are")
    }

    @Test
    fun theUserCanStopTheServerAndStartItAgain() = hosted { f ->
        val urls = mutableListOf<String>()
        backgroundScope.launch { f.host.restarted.collect { urls += it } }
        f.host.acquire()

        f.host.stopByUser()
        runCurrent()
        assertFalse(f.latest.alive, "stop ends the server")
        val stopped = f.host.health.value
        assertTrue(stopped.stoppedByUser)
        assertFalse(stopped.active)
        assertFalse(stopped.needsForceRestart, "a stop the user asked for is not a failure")
        assertFailsWith<LocalAppServerStoppedException> { f.host.acquire() }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, f.launches.size, "nothing brings a stopped server back on its own")

        f.host.startByUser()
        runCurrent()
        assertEquals(2, f.launches.size)
        assertTrue(f.host.health.value.active)
        assertFalse(f.host.health.value.stoppedByUser)
        assertEquals(listOf("ws://127.0.0.1:40002"), urls, "the client is told to reconnect")
    }

    @Test
    fun wakeDoesNotRestartAServerTheUserStopped() = hosted { f ->
        f.host.acquire()
        f.host.stopByUser()
        runCurrent()
        f.host.onResumed()
        runCurrent()

        assertEquals(1, f.launches.size)
    }

    @Test
    fun aRestartThatDiesBeforeReadyCountsAsACrash() = hosted { f ->
        f.host.acquire()
        f.failNextStart = 3
        f.latest.crash()
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        val health = f.host.health.value
        assertEquals(2, health.consecutiveCrashes)
        assertTrue(health.restartPending)
        assertTrue(health.lastError.orEmpty().contains("exit=3"))
    }

    @Test
    fun closeStopsTheServerWhateverLeasesRemain() = hosted { f ->
        f.host.acquire()

        f.host.close()
        runCurrent()

        assertFalse(f.latest.alive)
        assertFalse(f.host.health.value.restartPending)
    }

    @Test
    fun listenParserAcceptsOnlyLoopbackAnnouncements() {
        assertEquals("ws://127.0.0.1:4500", parseLocalAppServerListenUrl("Listening on ws://127.0.0.1:4500"))
        assertEquals("ws://localhost:4501", parseLocalAppServerListenUrl("Listening on ws://localhost:4501"))
        assertNull(parseLocalAppServerListenUrl("Listening on ws://192.168.1.5:4500"))
        assertNull(parseLocalAppServerListenUrl("Listening on https://127.0.0.1:4500"))
    }

    private class Start(
        val command: List<String>,
        val environment: Map<String, String>,
        val workingDirectory: String?,
        val tokenFile: String,
        val token: String,
    )

    private class Fixture(scope: TestScope) {
        val starts = mutableListOf<Start>()
        val launches = mutableListOf<FakeProcess>()
        val log = mutableListOf<String>()
        var failNextStart: Int? = null
        var silentNextStart = false
        var readyAfterPolls = 1
        var readyzPolls = 0
        private var tokens = 0
        val latest: FakeProcess get() = launches.last()

        val host = LocalAppServerHost(
            launcher = { launch, tokenFile, token ->
                starts += Start(launch.command, launch.environment, launch.workingDirectory, tokenFile, token)
                nextProcess().also { launches += it }
            },
            launch = {
                LocalAppServerLaunch(
                    command = listOf("node", "letta.js"),
                    backendDir = "/data/backend",
                    environment = mapOf("NODE_OPTIONS" to "--max-old-space-size=512"),
                    workingDirectory = "/work",
                )
            },
            readyz = { readyzPolls += 1; readyzPolls >= readyAfterPolls },
            scope = scope.backgroundScope,
            logLine = { log += it },
            readyTimeoutMs = 2_000,
            clockMs = { scope.testScheduler.currentTime },
            newToken = { "token-${++tokens}" },
        )

        private fun nextProcess(): FakeProcess {
            failNextStart?.let { code ->
                failNextStart = null
                return FakeProcess(stdoutLines = emptyList(), exitCode = code)
            }
            if (silentNextStart) {
                silentNextStart = false
                return FakeProcess(stdoutLines = null)
            }
            return FakeProcess(stdoutLines = listOf("booting", "Listening on ws://127.0.0.1:${40_001 + launches.size}"))
        }
    }

    private fun hosted(block: suspend TestScope.(Fixture) -> Unit) = runTest { block(Fixture(this)) }

    /**
     * [stdoutLines] null = a server that never writes or closes stdout; [exitCode] set = one that
     * has already exited.
     */
    private class FakeProcess(stdoutLines: List<String>?, exitCode: Int? = null) : LocalAppServerProcess {
        private val exit = CompletableDeferred<Int?>()
        private val stderrLines = Channel<String>(Channel.UNLIMITED)
        var alive = exitCode == null

        init {
            exitCode?.let(exit::complete)
        }

        override val stdout: Flow<String> = stdoutLines?.let { flowOf(*it.toTypedArray()) } ?: Channel<String>().receiveAsFlow()
        override val stderr: Flow<String> = stderrLines.receiveAsFlow()
        override val isAlive: Boolean get() = alive

        override suspend fun awaitExit(): Int? = exit.await()

        override fun kill() = end(1)

        /** The server died on its own. */
        fun crash(exitCode: Int = 1) = end(exitCode)

        fun err(line: String) {
            stderrLines.trySend(line)
        }

        private fun end(exitCode: Int) {
            if (!alive) return
            alive = false
            exit.complete(exitCode)
        }
    }
}
