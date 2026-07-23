package com.letta.mobile.appservercli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExecutableProbeTest {

    private fun runner(responses: Map<String, CommandResult?>): CommandRunner =
        CommandRunner { command, _ -> responses[command.joinToString(" ")] }

    @Test
    fun `captures letta and node versions`() {
        val probe = ExecutableProbe(
            runner(
                mapOf(
                    "letta --version" to CommandResult(0, "letta 0.28.8"),
                    "node --version" to CommandResult(0, "v22.19.0"),
                    "letta --help" to CommandResult(0, "Commands:\n  app-server\n  server"),
                    "letta server --help" to CommandResult(0, "Options:\n  --backend <type>"),
                ),
            ),
        )
        val result = probe.probe("letta")
        assertEquals("letta 0.28.8", result.lettaVersion)
        assertEquals("v22.19.0", result.nodeVersion)
    }

    @Test
    fun `prefers server backend local when supported`() {
        val probe = ExecutableProbe(
            runner(
                mapOf(
                    "letta --version" to CommandResult(0, "letta 0.29"),
                    "node --version" to CommandResult(0, "v22"),
                    "letta --help" to CommandResult(0, "  app-server\n  server"),
                    "letta server --help" to CommandResult(0, "--backend local"),
                ),
            ),
        )
        val result = probe.probe("letta")
        assertTrue(result.supportsServerBackendLocal)
        assertTrue(result.supportsAppServer)
        assertEquals(listOf("server", "--backend", "local", "--listen"), result.appServerInvocation())
    }

    @Test
    fun `falls back to app-server when server backend not available`() {
        val probe = ExecutableProbe(
            runner(
                mapOf(
                    "letta --version" to CommandResult(0, "letta 0.28.8"),
                    "node --version" to CommandResult(0, "v18"),
                    "letta --help" to CommandResult(0, "  app-server"),
                    "letta server --help" to CommandResult(2, "unknown command server"),
                ),
            ),
        )
        val result = probe.probe("letta")
        assertFalse(result.supportsServerBackendLocal)
        assertTrue(result.supportsAppServer)
        assertEquals(listOf("app-server", "--listen"), result.appServerInvocation())
    }

    @Test
    fun `throws when neither syntax is supported`() {
        val probe = ExecutableProbe(
            runner(
                mapOf(
                    "letta --version" to CommandResult(0, "letta 0.1"),
                    "node --version" to CommandResult(0, "v18"),
                    "letta --help" to CommandResult(0, "  chat\n  memory"),
                    "letta server --help" to CommandResult(2, "unknown"),
                ),
            ),
        )
        val result = probe.probe("letta")
        assertThrows(IllegalStateException::class.java) { result.appServerInvocation() }
    }

    @Test
    fun `null version when probe command fails`() {
        val probe = ExecutableProbe(
            runner(
                mapOf(
                    "letta --version" to CommandResult(127, ""),
                    "node --version" to null,
                    "letta --help" to CommandResult(0, "  app-server"),
                    "letta server --help" to null,
                ),
            ),
        )
        val result = probe.probe("letta")
        assertNull(result.lettaVersion)
        assertNull(result.nodeVersion)
        assertTrue(result.supportsAppServer)
    }
}
