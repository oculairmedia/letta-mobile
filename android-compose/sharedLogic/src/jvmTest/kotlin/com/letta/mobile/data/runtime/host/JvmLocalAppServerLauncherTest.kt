package com.letta.mobile.data.runtime.host

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** letta-mobile-by4vm.110.4: the JVM child-process launcher, against real `sh` children (POSIX). */
class JvmLocalAppServerLauncherTest {
    private val root: File = createTempDirectory("local-app-server-launcher").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun writesTheTokenOwnerOnlyAndRunsTheCommandWithItsEnvironment() = runBlocking {
        val tokenFile = File(root, "backend/ws-token")
        val spawned = mutableListOf<Process>()
        val process = JvmLocalAppServerLauncher(onSpawned = { spawned += it }).start(
            launch = LocalAppServerLaunch(
                command = listOf("sh", "-c", "echo \"Listening on ws://127.0.0.1:1 \$GREETING\"; pwd; echo oops >&2"),
                backendDir = root.absolutePath,
                environment = mapOf("GREETING" to "hi"),
                workingDirectory = root.absolutePath,
            ),
            tokenFile = tokenFile.absolutePath,
            token = "secret",
        )

        assertEquals(listOf("Listening on ws://127.0.0.1:1 hi", root.canonicalPath), process.stdout.toList())
        assertEquals(listOf("oops"), process.stderr.toList())
        assertEquals(0, withTimeout(10_000) { process.awaitExit() })
        assertEquals("secret", tokenFile.readText())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile.toPath())))
        assertEquals(1, spawned.size)
    }

    @Test
    fun killEndsTheServerAndItsDescendants() = runBlocking {
        var spawned: Process? = null
        val process = JvmLocalAppServerLauncher(onSpawned = { spawned = it }, stopTimeoutMs = 2_000).start(
            launch = LocalAppServerLaunch(listOf("sh", "-c", "sleep 60 & echo started; wait"), root.absolutePath),
            tokenFile = File(root, "ws-token").absolutePath,
            token = "t",
        )
        // Collect to EOF like the host does: cancelling a blocked read early would hang on the pipe.
        val firstLine = CompletableDeferred<String>()
        val reader = launch(Dispatchers.IO) { process.stdout.collect { firstLine.complete(it) } }
        assertEquals("started", withTimeout(10_000) { firstLine.await() })
        val descendants = checkNotNull(spawned).descendants().toList()
        assertTrue(descendants.isNotEmpty(), "the shell started a child")

        process.kill()

        assertFalse(process.isAlive)
        // Signals land asynchronously: wait for each descendant's exit rather than sampling isAlive.
        descendants.forEach { it.onExit().get(10, TimeUnit.SECONDS) }
        assertTrue(descendants.none { it.isAlive }, "no descendant outlives the server")
        assertTrue(withTimeout(10_000) { process.awaitExit() } != 0, "a killed server does not exit cleanly")
        withTimeout(10_000) { reader.join() }
    }

    @Test
    fun aMissingExecutableIsAStartFailure() {
        assertFailsWith<LocalAppServerStartException> {
            JvmLocalAppServerLauncher().start(
                launch = LocalAppServerLaunch(listOf(File(root, "no-such-node").absolutePath), root.absolutePath),
                tokenFile = File(root, "ws-token").absolutePath,
                token = "t",
            )
        }
    }
}
