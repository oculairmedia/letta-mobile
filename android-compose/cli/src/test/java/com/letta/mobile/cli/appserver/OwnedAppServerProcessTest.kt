package com.letta.mobile.cli.appserver

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * lgns8.18: OwnedAppServerProcess spawns a REAL child process, so these tests use a
 * trivial `sh` stand-in that mimics the letta app-server's stdout announce / exit
 * behavior — no letta binary needed, fully hermetic.
 */
class OwnedAppServerProcessTest {
    private fun sh(script: String): List<String> = listOf("/bin/sh", "-c", script)

    @Test
    fun `buildCommand appends app-server on an ephemeral loopback port`() {
        assertEquals(
            listOf("letta", "app-server", "--listen", "ws://127.0.0.1:0"),
            OwnedAppServerProcess.buildCommand("letta"),
        )
        assertEquals(
            listOf("node", "letta.js", "app-server", "--listen", "ws://127.0.0.1:0"),
            OwnedAppServerProcess.buildCommand("node", extraArgs = listOf("letta.js")),
        )
    }

    @Test
    fun `spawn parses the announced listen url and owns the live child`() {
        val owned = OwnedAppServerProcess.spawn(
            command = sh("""echo 'Listening on ws://127.0.0.1:41234'; echo 'Control: ws://127.0.0.1:41234/ws?channel=control'; sleep 30"""),
        )
        try {
            assertEquals("ws://127.0.0.1:41234", owned.wsBaseUrl)
            assertTrue(owned.isAlive, "child should be alive after announce")
        } finally {
            owned.close(graceMillis = 2_000)
        }
        assertFalse(owned.isAlive, "close() must terminate the child")
    }

    @Test
    fun `spawn ignores noise and picks the Listening line`() {
        val owned = OwnedAppServerProcess.spawn(
            command = sh("""echo 'booting...'; echo 'Listening on ws://127.0.0.1:5999'; sleep 30"""),
        )
        try {
            assertEquals("ws://127.0.0.1:5999", owned.wsBaseUrl)
        } finally {
            owned.close(graceMillis = 2_000)
        }
    }

    @Test
    fun `spawn throws when the child exits before announcing`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            OwnedAppServerProcess.spawn(command = sh("""echo 'startup failed'; exit 3"""))
        }
        assertTrue(ex.message!!.contains("exited"), "message should note the child exited: ${ex.message}")
    }

    @Test
    fun `spawn throws on readiness timeout and reaps the child`() {
        val ex = assertThrows(IllegalStateException::class.java) {
            OwnedAppServerProcess.spawn(
                command = sh("""sleep 30"""),
                readyTimeoutMillis = 400,
            )
        }
        assertTrue(ex.message!!.contains("did not announce"), "message should note the timeout: ${ex.message}")
    }
}
