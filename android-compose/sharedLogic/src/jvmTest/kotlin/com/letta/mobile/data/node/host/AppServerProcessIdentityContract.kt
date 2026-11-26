package com.letta.mobile.data.node.host

import com.letta.mobile.data.subagents.AppServerProcessIdentity
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What every OS's App Server process identity must do, run against each binding with a fake process
 * table: PID 42 is the App Server, named by an operator-bound PID file.
 */
abstract class AppServerProcessIdentityContract {
    protected val root: Path = Files.createTempDirectory("process-identity")
    private val pidFile: Path = root.resolve("main.pid").also { Files.writeString(it, "42\n") }

    /** Makes PID 42 a live process started at [start] (an OS-specific clock tick). */
    protected abstract fun startProcess(start: Long)

    /** Makes PID 42 a process that has exited. */
    protected abstract fun endProcess()

    /** The binding under test for an App Server at [serverUrl] named by [pidFile]. */
    protected abstract fun identity(serverUrl: String?, pidFile: Path?): AppServerProcessIdentity

    private fun local() = identity(LOCAL_URL, pidFile)

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun repeatedReadsOfOneProcessAreStable() {
        startProcess(100)

        val first = local().current()

        assertNotNull(first)
        assertEquals(first, local().current())
    }

    @Test
    fun aReusedPidIsADifferentProcess() {
        startProcess(100)
        val before = local().current()

        startProcess(200)

        assertNotNull(before)
        assertNotEquals(before, local().current())
        assertNotNull(local().current())
    }

    @Test
    fun anExitedProcessIsUnknown() {
        startProcess(100)
        endProcess()

        assertNull(local().current())
    }

    @Test
    fun aMissingOrMalformedPidFileIsUnknown() {
        startProcess(100)

        assertNull(identity(LOCAL_URL, root.resolve("missing.pid")).current())
        Files.writeString(pidFile, "not a pid")
        assertNull(local().current())
        Files.writeString(pidFile, "-1")
        assertNull(local().current())
    }

    @Test
    fun remoteUnsupportedAndUnconfiguredServersAreUnknown() {
        startProcess(100)

        listOf("ws://example.org:4500", "ws://127.0.0.1.example.org:4500", "not a url", "http://127.0.0.1:4500").forEach {
            assertNull(identity(it, pidFile).current(), it)
        }
        assertNull(identity(null, pidFile).current())
        assertNull(identity(LOCAL_URL, null).current())
    }

    private companion object {
        const val LOCAL_URL = "ws://127.0.0.1:4500"
    }
}

/** The Linux binding over a fake `/proc` (boot id plus `/proc/<pid>/stat`). */
class LinuxAppServerProcessIdentityContractTest : AppServerProcessIdentityContract() {
    private val proc: Path = root.resolve("proc").also {
        Files.createDirectories(it.resolve("sys/kernel/random"))
        Files.writeString(it.resolve("sys/kernel/random/boot_id"), BOOT)
    }

    override fun startProcess(start: Long) = writeStat("S", start)

    override fun endProcess() = writeStat("Z", 100)

    override fun identity(serverUrl: String?, pidFile: Path?): AppServerProcessIdentity =
        LinuxAppServerProcessIdentity(serverUrl, pidFile, proc)

    private fun writeStat(state: String, start: Long) {
        Files.createDirectories(proc.resolve("42"))
        // Fields 3..21, then field 22. The comm field may itself contain parentheses/spaces.
        Files.writeString(proc.resolve("42/stat"), "42 (node (worker)) $state " + List(18) { "0" }.joinToString(" ") + " $start 0\n")
    }

    @Test
    fun aRebootIsADifferentProcess() {
        startProcess(100)
        val before = identity("ws://127.0.0.1:4500", root.resolve("main.pid")).current()

        Files.writeString(proc.resolve("sys/kernel/random/boot_id"), "b3a51d48-1e92-4ad0-a3b2-38205ca95139")

        assertNotNull(before)
        assertNotEquals(before, identity("ws://127.0.0.1:4500", root.resolve("main.pid")).current())
    }

    private companion object {
        const val BOOT = "a3a51d48-1e92-4ad0-a3b2-38205ca95139"
    }
}

/** The Windows binding over a fake process table (PID to creation time). */
class WindowsAppServerProcessIdentityContractTest : AppServerProcessIdentityContract() {
    private val processes = mutableMapOf<Long, Instant>()

    override fun startProcess(start: Long) {
        processes[42] = Instant.ofEpochMilli(EPOCH_MS + start)
    }

    override fun endProcess() {
        processes.remove(42)
    }

    override fun identity(serverUrl: String?, pidFile: Path?): AppServerProcessIdentity =
        WindowsAppServerProcessIdentity({ TrustedPidFile.read(serverUrl, pidFile) }, processes::get)

    @Test
    fun anOwnedChildIsProvenByItsPidAndCreationTime() {
        startProcess(100)
        var child: Long? = null
        val owned = WindowsAppServerProcessIdentity({ child }, processes::get)

        val beforeSpawn = owned.current()
        child = 42

        assertNull(beforeSpawn)
        assertEquals("pid:42:started:${Instant.ofEpochMilli(EPOCH_MS + 100)}", owned.current())
    }

    @Test
    fun aProcessReplacedMidReadFailsClosed() {
        var reads = 0
        val racing = WindowsAppServerProcessIdentity({ 42 }) { Instant.ofEpochMilli(EPOCH_MS + reads++) }

        assertNull(racing.current())
    }

    private companion object {
        const val EPOCH_MS = 1_790_000_000_000L
    }
}
