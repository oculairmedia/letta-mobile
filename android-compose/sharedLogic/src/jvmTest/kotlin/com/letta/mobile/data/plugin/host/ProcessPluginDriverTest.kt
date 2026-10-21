package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.SecretScrubber
import com.letta.mobile.data.plugin.wire.LcpCallException
import com.letta.mobile.data.plugin.wire.LcpErrorCode
import com.letta.mobile.plugin.api.ActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** [ProcessPluginDriver] against a real subprocess, [FakeLcpPlugin] (letta-mobile-cdgpr.4). */
class ProcessPluginDriverTest {
    private fun withFixture(
        mode: String = "normal",
        options: (ProcessDriverOptions) -> ProcessDriverOptions = { it },
        confinement: ProcessConfinementSource = ProcessConfinementSource { ProcessConfinement.Unconfined },
        block: suspend CoroutineScope.(ProcessDriverFixture) -> Unit,
    ) = runBlocking {
        val fixture = ProcessDriverFixture(this, mode, options, confinement)
        try {
            withTimeout(2.minutes) { block(fixture) }
        } finally {
            fixture.driver.stop()
            fixture.delete()
        }
    }

    private fun ActionResult.text(): String = assertIs<ActionResult.Ok>(this).text

    @Test
    fun startActivateInvokeAndDeactivateRoundTrip() = withFixture { fixture ->
        val running = assertIs<PluginDriverState.Running>(fixture.driver.start())
        assertEquals("ran plot", fixture.driver.invoke(ProcessDriverFixture.call("plot")).text())
        fixture.driver.stop()
        assertEquals(PluginDriverState.Stopped("deactivated"), fixture.driver.state.value)
        assertFalse(ProcessHandle.of(requireNotNull(running.pid)).map(ProcessHandle::isAlive).orElse(false))
        assertFailsWith<LcpCallException> { fixture.driver.invoke(ProcessDriverFixture.call("plot")) }
    }

    @Test
    fun thePluginEnvironmentIsPathPlusDeclaredSubstitutionsOnly() = withFixture { fixture ->
        fixture.driver.start()
        val keys = fixture.driver.invoke(ProcessDriverFixture.call("env")).text().split(",").toSet()
        assertEquals(setOf("API_TOKEN", "GREETING", "PATH"), keys)
        val hostVariables = System.getenv().keys - "PATH"
        assertTrue(hostVariables.isNotEmpty(), "the test host has variables to leak")
        assertTrue(hostVariables.none { it in keys }, "host variables leaked: ${hostVariables intersect keys}")
        assertEquals("hello from settings", fixture.driver.invoke(ProcessDriverFixture.call("greeting")).text())
    }

    @Test
    fun theWorkingDirectoryIsThePackageDirectory() = withFixture { fixture ->
        fixture.driver.start()
        val cwd = fixture.driver.invoke(ProcessDriverFixture.call("cwd")).text()
        assertEquals(fixture.packageDir.toFile().canonicalPath, cwd)
        assertEquals(1, fixture.starts)
    }

    @Test
    fun aPluginIgnoringSigtermIsKilledAfterTheGrace() = withFixture("ignore-term", { it.copy(killGrace = 1.seconds) }) { fixture ->
        val running = assertIs<PluginDriverState.Running>(fixture.driver.start())
        val began = System.nanoTime()
        fixture.driver.stop()
        val waitedMillis = (System.nanoTime() - began) / 1_000_000
        assertTrue(waitedMillis >= 900, "stopped after $waitedMillis ms, before the grace ran out")
        assertFalse(ProcessHandle.of(requireNotNull(running.pid)).map(ProcessHandle::isAlive).orElse(false))
        assertEquals(PluginDriverState.Stopped("deactivated"), fixture.driver.state.value)
    }

    @Test
    fun threeCrashesFaultThePluginAndStopRestarts() = withFixture(
        "crash",
        { it.copy(crashPolicy = CrashPolicy(baseDelay = 10.milliseconds, maxDelay = 50.milliseconds)) },
    ) { fixture ->
        fixture.driver.start()
        val faulted = assertIs<PluginDriverState.Faulted>(fixture.awaitState { it.terminal })
        assertTrue("crashed 3 times" in faulted.reason, faulted.reason)
        delay(1.seconds)
        assertEquals(3, fixture.starts)
        assertIs<PluginDriverState.Faulted>(fixture.driver.state.value)
    }

    @Test
    fun aHealthTimeoutRestartsThePlugin() = withFixture(
        "hang-health",
        { it.copy(healthInterval = 200.milliseconds, healthTimeout = 300.milliseconds) },
    ) { fixture ->
        // Capture the transient first restart at emission, even when its consumer is delayed.
        val firstRestart = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            assertIs<PluginDriverState.Restarting>(fixture.awaitState { it is PluginDriverState.Restarting || it.terminal })
        }
        val secondRestart = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            assertIs<PluginDriverState.Restarting>(
                fixture.awaitState { (it is PluginDriverState.Restarting && it.attempt == 2) || it.terminal },
            )
        }
        fixture.driver.start()
        assertEquals(2, secondRestart.await().attempt)
        val restarting = firstRestart.await()
        assertTrue("health check failed" in restarting.reason, restarting.reason)
        assertEquals(1, restarting.attempt)
    }

    @Test
    fun aMissedDeadlineFailsTheCallButTheDriverKeepsRunning() = withFixture(options = { it.copy(invokeTimeout = 300.milliseconds) }) { fixture ->
        val running = fixture.driver.start()
        val failure = assertFailsWith<LcpCallException> { fixture.driver.invoke(ProcessDriverFixture.call("slow")) }
        assertEquals(LcpErrorCode.DEADLINE_EXCEEDED, failure.code)
        assertEquals(running, fixture.driver.state.value)
        assertEquals("ran plot", fixture.driver.invoke(ProcessDriverFixture.call("plot")).text())
    }

    @Test
    fun stderrLinesReachTheCallbackScrubbed() = withFixture { fixture ->
        fixture.driver.start()
        fixture.driver.invoke(ProcessDriverFixture.call("leak"))
        withTimeout(10.seconds) { while (fixture.stderr.size < 2) delay(20) }
        assertEquals("fake plugin up in normal", fixture.stderr.first())
        assertEquals(SecretScrubber.REFUSED, fixture.stderr[1])
        assertTrue(fixture.stderr.none { ProcessDriverFixture.SECRET in it })
    }

    @Test
    fun anUnresolvableSubstitutionFaultsWithoutSpawning() = runBlocking {
        val fixture = ProcessDriverFixture(this, env = mapOf("MISSING" to "\${secrets.absent}"))
        try {
            val faulted = assertIs<PluginDriverState.Faulted>(fixture.driver.start())
            assertTrue("MISSING" in faulted.reason, faulted.reason)
            assertEquals(0, fixture.starts)
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun underASystemdScopeTheBusVariablesNeverReachThePlugin() {
        val probe = SystemdScopeProbe(System.getenv())
        assumeTrue("systemd-run --user --scope is unavailable here", probe.confinement() is ProcessConfinement.SystemdScope)
        withFixture(confinement = probe) { fixture ->
            fixture.driver.start()
            val keys = fixture.driver.invoke(ProcessDriverFixture.call("env")).text().split(",").toSet()
            assertEquals(setOf("API_TOKEN", "GREETING", "PATH"), keys - SYSTEMD_ADDED)
        }
    }

    private companion object {
        /** What systemd itself sets in a scope's processes. */
        val SYSTEMD_ADDED = setOf("INVOCATION_ID", "JOURNAL_STREAM")
    }
}
