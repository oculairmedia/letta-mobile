package com.letta.mobile.desktop.runtime

import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DesktopLocalRuntimeManagerTest {
    @Test
    fun `startup builds pinned local command and returns loopback announcement`() = withFixture { fixture ->
        val process = FakeProcess(stdoutText = "booting\nListening on ws://127.0.0.1:43123\n")
        fixture.processes += process

        val url = fixture.manager.ensureStarted()

        assertEquals("ws://127.0.0.1:43123", url)
        assertEquals(
            listOf(
                fixture.installation.nodeExecutable.absolutePath,
                fixture.installation.lettaEntryPoint.absolutePath,
                "server",
                "--backend",
                "local",
                "--listen",
                "ws://127.0.0.1:0",
            ),
            fixture.commands.single(),
        )
        assertEquals("1", fixture.environments.single()["LETTA_LOCAL_BACKEND_EXPERIMENTAL"])
        assertEquals(fixture.backend.absolutePath, fixture.environments.single()["LETTA_LOCAL_BACKEND_DIR"])
    }

    @Test
    fun `live child is reused and dead child is replaced`() = withFixture { fixture ->
        val first = FakeProcess(stdoutText = "Listening on ws://127.0.0.1:43123\n")
        val second = FakeProcess(stdoutText = "Listening on ws://127.0.0.1:43124\n")
        fixture.processes += first
        fixture.processes += second

        assertEquals("ws://127.0.0.1:43123", fixture.manager.ensureStarted())
        assertEquals("ws://127.0.0.1:43123", fixture.manager.ensureStarted())
        assertEquals(1, fixture.commands.size)

        first.alive = false
        assertEquals("ws://127.0.0.1:43124", fixture.manager.ensureStarted())
        assertEquals(2, fixture.commands.size)
    }

    @Test
    fun `early exit is reported and host remains restartable`() = withFixture { fixture ->
        fixture.processes += FakeProcess(stdoutText = "", alive = false, exitCode = 23)
        fixture.processes += FakeProcess(stdoutText = "Listening on ws://localhost:43125\n")

        val failure = assertFailsWith<IllegalStateException> { fixture.manager.ensureStarted() }
        assertTrue(failure.message.orEmpty().contains("exit=23"))
        assertEquals("ws://localhost:43125", fixture.manager.ensureStarted())
    }

    @Test
    fun `dead root still reaps live descendants`() = withFixture { fixture ->
        val descendant = FakeHandle()
        val process = FakeProcess(
            stdoutText = "Listening on ws://127.0.0.1:43123\n",
            descendants = listOf(descendant),
        )
        fixture.processes += process
        fixture.manager.ensureStarted()
        process.alive = false

        fixture.manager.close()

        assertEquals(1, descendant.destroyCount)
        assertEquals(1, descendant.forceCount)
    }

    @Test
    fun `listen parser rejects non-loopback and malformed announcements`() {
        assertEquals("ws://127.0.0.1:4500", parseDesktopRuntimeListenUrl("Listening on ws://127.0.0.1:4500"))
        assertEquals("ws://localhost:4501", parseDesktopRuntimeListenUrl("Listening on ws://localhost:4501"))
        assertEquals(null, parseDesktopRuntimeListenUrl("Listening on ws://192.168.1.5:4500"))
        assertEquals(null, parseDesktopRuntimeListenUrl("Listening on https://127.0.0.1:4500"))
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = createTempDirectory("desktop-runtime-manager").toFile()
        try {
            val installation = DesktopLettaCodeInstallation(
                File(root, "node.exe").apply { createNewFile() },
                File(root, "letta.js").apply { createNewFile() },
            )
            val fixture = Fixture(root, installation)
            block(fixture)
            fixture.manager.close()
        } finally {
            root.deleteRecursively()
        }
    }

    private class Fixture(root: File, val installation: DesktopLettaCodeInstallation) {
        val backend = File(root, "backend")
        val commands = mutableListOf<List<String>>()
        val environments = mutableListOf<Map<String, String>>()
        val processes = ArrayDeque<FakeProcess>()
        val manager = DesktopLocalRuntimeManager(
            installationProvider = { installation },
            backendDirectory = { backend },
            processLauncher = DesktopRuntimeProcessLauncher { command, environment ->
                commands += command
                environments += environment
                processes.removeFirst()
            },
            logLine = {},
            readyTimeoutMs = 25,
            stopTimeoutMs = 1,
        )
    }

    private class FakeProcess(
        stdoutText: String,
        override val descendants: List<DesktopRuntimeProcessHandle> = emptyList(),
        alive: Boolean = true,
        private val exitCode: Int? = null,
    ) : DesktopRuntimeProcess {
        override val stdout = BufferedReader(StringReader(stdoutText))
        override val stderr = BufferedReader(StringReader(""))
        var alive = alive
        var destroyCount = 0
        var forceCount = 0
        override val isAlive: Boolean get() = alive
        override val exitCodeOrNull: Int? get() = exitCode.takeIf { !alive }
        override fun destroy() { destroyCount += 1; alive = false }
        override fun destroyForcibly() { forceCount += 1; alive = false }
        override fun waitFor(timeoutMs: Long): Boolean = !alive
    }

    private class FakeHandle : DesktopRuntimeProcessHandle {
        var alive = true
        var destroyCount = 0
        var forceCount = 0
        override val isAlive: Boolean get() = alive
        override fun destroy() { destroyCount += 1 }
        override fun destroyForcibly() { forceCount += 1; alive = false }
    }
}
