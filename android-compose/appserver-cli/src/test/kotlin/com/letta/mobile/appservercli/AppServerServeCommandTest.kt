package com.letta.mobile.appservercli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AppServerServeCommandTest {

    /** Fake liveness: pid -> start time (present = alive). */
    private class FakeLiveness(val table: Map<Long, Long>) : ProcessLiveness {
        override fun startTimeMsOf(pid: Long): Long? = table[pid]
    }

    // --- P0.1 (letta-mobile-gn7kr.1): the serve path fences backend ownership. ---

    @Test
    fun `fence is skipped when no backend dir is configured`() {
        assertNull(acquireBackendFence(null, null, BackendOwnershipPreflight()))
        assertNull(acquireBackendFence("   ", null, BackendOwnershipPreflight()))
    }

    @Test
    fun `fence rejects a live competing writer with a non-zero program result`(@TempDir dir: Path) {
        val live = FakeLiveness(mapOf(200L to 5_000L))
        Files.writeString(
            dir.resolve(BackendOwnershipPreflight.OWNER_FILENAME),
            BackendOwnerInfo(200, 5_000, dir.toString(), "other", "t").toJson(),
        )
        val preflight = BackendOwnershipPreflight(live, selfPid = 100)
        assertThrows(ProgramResult::class.java) {
            acquireBackendFence(dir.toString(), null, preflight)
        }
    }

    @Test
    fun `fence acquires a clear backend root and names self`(@TempDir dir: Path) {
        val preflight = BackendOwnershipPreflight(FakeLiveness(mapOf(100L to 7_000L)), selfPid = 100)
        val fence = acquireBackendFence(dir.toString(), "meridian-appserver.service", preflight)
        assertNotNull(fence)
        fence!!.use {
            assertEquals(100L, it.info.pid)
            assertEquals("meridian-appserver.service", it.info.unit)
        }
        // Clean close removes the sidecar.
        assertFalse(Files.exists(dir.resolve(BackendOwnershipPreflight.OWNER_FILENAME)))
    }
    @Test
    fun `default command launches host letta app server on loopback`() {
        val command = buildAppServerServeCommand(AppServerServeSpec())

        assertEquals(
            listOf(
                "letta",
                "app-server",
                "--listen",
                "ws://127.0.0.1:4500",
            ),
            command,
        )
    }

    @Test
    fun `command passes through install and websocket auth arguments`() {
        val command = buildAppServerServeCommand(
            AppServerServeSpec(
                listen = "ws://0.0.0.0:4500",
                lettaCommand = "pnpm",
                lettaArguments = listOf("dlx", "@letta-ai/letta-code@0.27.15"),
                wsAuth = "signed-bearer-token",
                wsSharedSecretFile = "secret.txt",
                wsIssuer = "meridian",
                wsAudience = "letta-mobile",
                wsMaxClockSkewSeconds = 60,
            ),
        )

        assertEquals(
            listOf(
                "pnpm",
                "dlx",
                "@letta-ai/letta-code@0.27.15",
                "app-server",
                "--listen",
                "ws://0.0.0.0:4500",
                "--ws-auth",
                "signed-bearer-token",
                "--ws-shared-secret-file",
                "secret.txt",
                "--ws-issuer",
                "meridian",
                "--ws-audience",
                "letta-mobile",
                "--ws-max-clock-skew-seconds",
                "60",
            ),
            command,
        )
    }

    @Test
    fun `invalid auth mode fails before launching process`() {
        assertThrows(UsageError::class.java) {
            buildAppServerServeCommand(
                AppServerServeSpec(wsAuth = "basic"),
            )
        }
    }

    @Test
    fun `non loopback listen requires websocket auth`() {
        assertThrows(UsageError::class.java) {
            buildAppServerServeCommand(
                AppServerServeSpec(listen = "ws://0.0.0.0:4500"),
            )
        }
    }

    @Test
    fun `localhost listen can run without websocket auth`() {
        val command = buildAppServerServeCommand(
            AppServerServeSpec(listen = "ws://localhost:4500"),
        )

        assertEquals(
            listOf(
                "letta",
                "app-server",
                "--listen",
                "ws://localhost:4500",
            ),
            command,
        )
    }

    @Test
    fun `formatted command quotes whitespace arguments`() {
        val rendered = formatProcessCommand(
            listOf("letta", "app-server", "--ws-token-file", "C:\\Users\\Test User\\token.txt"),
        )

        assertEquals(
            "letta app-server --ws-token-file \"C:\\Users\\Test User\\token.txt\"",
            rendered,
        )
    }
}
