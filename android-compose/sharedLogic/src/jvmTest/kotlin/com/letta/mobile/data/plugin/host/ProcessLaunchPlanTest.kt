package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.plugin.PluginRuntime
import com.letta.mobile.data.plugin.ResolvedTemplates
import com.letta.mobile.data.plugin.wire.LcpInboundFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** The spawn plan, the stdio framing and the crash ledger, without a plugin (letta-mobile-cdgpr.4). */
class ProcessLaunchPlanTest {
    private val packageDir = Files.createTempDirectory("lcp-plan").also { it.resolve("server.mjs").toFile().writeText("") }
    private val env = ResolvedTemplates(mapOf("API_TOKEN" to "secret"))
    private val scope = ProcessConfinement.SystemdScope("/usr/bin/systemd-run", "/usr/bin/env", mapOf("XDG_RUNTIME_DIR" to "/run/user/0"))
    private val node = PluginRuntime.Process(command = "node", args = listOf("./server.mjs"))
    private val path: String = File.createTempFile("node", "").let { fake ->
        val dir = Files.createTempDirectory("lcp-path").toFile()
        File(dir, "node").also { fake.copyTo(it); it.setExecutable(true) }
        dir.absolutePath
    }

    @Test
    fun anUnconfinedNodePluginGetsItsHeapCapped() {
        val plan = ProcessLaunchPlanner.plan(node, packageDir, env, ProcessHostEnvironment(path, ProcessConfinement.Unconfined))
        assertEquals(listOf("$path/node", "--max-old-space-size=256", "./server.mjs"), plan.command)
        assertEquals(mapOf("PATH" to path, "API_TOKEN" to "secret"), plan.environment)
        assertEquals(packageDir.toFile().canonicalFile, plan.directory.canonicalFile)
        assertFalse("secret" in plan.toString())
    }

    @Test
    fun aSystemdScopeWrapsTheCommandWithCapsAndStripsTheBusVariables() {
        val plan = ProcessLaunchPlanner.plan(node, packageDir, env, ProcessHostEnvironment(path, scope))
        val expected = listOf(
            "/usr/bin/systemd-run", "--user", "--scope", "--quiet",
            "-p", "MemoryMax=256M", "-p", "CPUQuota=50%", "-p", "TasksMax=64", "--",
            "/usr/bin/env", "-u", "XDG_RUNTIME_DIR", "$path/node", "./server.mjs",
        )
        assertEquals(expected, plan.command)
        assertEquals(mapOf("PATH" to path, "API_TOKEN" to "secret", "XDG_RUNTIME_DIR" to "/run/user/0"), plan.environment)
    }

    @Test
    fun aPackagedCommandResolvesInsideThePackageAndMayNotLeaveIt() {
        val plan = ProcessLaunchPlanner.plan(PluginRuntime.Process("./server.mjs"), packageDir, env, ProcessHostEnvironment(path, ProcessConfinement.Unconfined))
        assertEquals(packageDir.resolve("server.mjs").toAbsolutePath().normalize().toString(), plan.command.single())
        assertFailsWith<PluginLaunchException> {
            ProcessLaunchPlanner.plan(PluginRuntime.Process("../escape.sh"), packageDir, env, ProcessHostEnvironment(path, ProcessConfinement.Unconfined))
        }
        assertFailsWith<PluginLaunchException> {
            ProcessLaunchPlanner.plan(PluginRuntime.Process("node", cwd = "../.."), packageDir, env, ProcessHostEnvironment(path, ProcessConfinement.Unconfined))
        }
        assertFailsWith<PluginLaunchException> {
            ProcessLaunchPlanner.plan(PluginRuntime.Process("no-such-program"), packageDir, env, ProcessHostEnvironment(path, ProcessConfinement.Unconfined))
        }
    }

    @Test
    fun stdioDropsAnOversizedLineAndForwardsStderr() = runBlocking {
        assumeTrue("needs /bin/sh", File("/bin/sh").canExecute())
        val script = "head -c 5000000 /dev/zero | tr '\\000' a; echo; echo '{\"jsonrpc\":\"2.0\"}'; echo oops >&2"
        val process = ProcessBuilder("/bin/sh", "-c", script).start()
        val stderr = CopyOnWriteArrayList<String>()
        val transport = StdioLcpTransport(process, this, stderr::add)
        assertEquals(LcpInboundFrame.Oversized(5_000_000), transport.receive())
        assertEquals(LcpInboundFrame.Text("{\"jsonrpc\":\"2.0\"}"), transport.receive())
        assertEquals(null, transport.receive())
        transport.stderrPump.join()
        transport.close()
        assertEquals(listOf("oops"), stderr)
    }

    @Test
    fun theLedgerFaultsOnTheThirdCrashInTheWindowAndJittersWithinTheCeiling() {
        var now = 0L
        val ledger = CrashLedger(CrashPolicy(window = 1.minutes, baseDelay = 100.milliseconds), { now }, Random(7))
        assertEquals(1, ledger.record())
        now += 2.minutes.inWholeMilliseconds
        assertEquals(1, ledger.record(), "a crash outside the window is forgotten")
        assertEquals(2, ledger.record())
        assertFalse(ledger.exhausted(2))
        assertTrue(ledger.exhausted(ledger.record()))
        repeat(50) { assertTrue(ledger.delayFor(3) <= 400.milliseconds) }
    }
}
