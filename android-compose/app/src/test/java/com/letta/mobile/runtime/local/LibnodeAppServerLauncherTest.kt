package com.letta.mobile.runtime.local

import com.letta.mobile.data.runtime.host.LocalAppServerHost
import com.letta.mobile.data.runtime.host.LocalAppServerLaunch
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** letta-mobile-by4vm.110.5: the in-process libnode launcher over a fake node. */
class LibnodeAppServerLauncherTest {
    private val backendDir = File(System.getProperty("java.io.tmpdir"), "libnode-launcher-${System.nanoTime()}")
    private val tokenFile = File(backendDir, LocalAppServerHost.TOKEN_FILE_NAME).absolutePath

    @Test
    fun startWritesAnOwnerOnlyTokenAndRunsTheHostCommandInTheBackendDir() {
        val node = FakeNode()

        val launcher = LibnodeAppServerLauncher(node)
        launcher.start(launch(), tokenFile, "secret")

        assertTrue("node runs once per process", launcher.singleLaunch)
        val token = File(tokenFile)
        assertEquals("secret", token.readText())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(token.toPath())))
        assertEquals(listOf("node", "letta.js", "server"), node.request?.arguments)
        assertEquals(mapOf("HOME" to "/home"), node.request?.environment)
        assertEquals(backendDir.absoluteFile, node.request?.workingDirectory?.absoluteFile)
    }

    @Test
    fun linesBeforeCollectionAreKeptAndStreamsEndWithNode() = runTest {
        val node = FakeNode()
        val process = LibnodeAppServerLauncher(node).start(launch(), tokenFile, "secret")

        node.output.stdout("Listening on ws://127.0.0.1:4500")
        node.output.stderr("warming up")
        assertTrue(process.isAlive)
        node.output.exited(7)

        assertEquals(listOf("Listening on ws://127.0.0.1:4500"), process.stdout.toList())
        assertEquals(listOf("warming up"), process.stderr.toList())
        assertFalse(process.isAlive)
        assertEquals(7, process.awaitExit())
    }

    @Test
    fun killDetachesBecauseInProcessNodeCannotStop() = runTest {
        val node = FakeNode()
        val process = LibnodeAppServerLauncher(node).start(launch(), tokenFile, "secret")

        process.kill()
        node.output.stdout("still running")
        node.output.exited(0)

        assertFalse(process.isAlive)
        assertNull("detached, not exited", process.awaitExit())
        assertEquals(emptyList<String>(), process.stdout.toList())
    }

    @Test
    fun aSecondNodeStartIsRefused() {
        val node = FakeNode(refuse = "node already ran")

        try {
            LibnodeAppServerLauncher(node).start(launch(), tokenFile, "secret")
            fail("expected the launch to be refused")
        } catch (e: IllegalStateException) {
            assertEquals("node already ran", e.message)
        }
    }

    private fun launch() = LocalAppServerLaunch(
        command = listOf("node", "letta.js", "server"),
        backendDir = backendDir.absolutePath,
        environment = mapOf("HOME" to "/home"),
    )

    private class FakeNode(private val refuse: String? = null) : EmbeddedNode {
        var request: LettaCodeNodeStartRequest? = null
        lateinit var output: EmbeddedNodeOutput

        override fun launch(request: LettaCodeNodeStartRequest, output: EmbeddedNodeOutput) {
            refuse?.let { throw IllegalStateException(it) }
            this.request = request
            this.output = output
        }
    }
}
