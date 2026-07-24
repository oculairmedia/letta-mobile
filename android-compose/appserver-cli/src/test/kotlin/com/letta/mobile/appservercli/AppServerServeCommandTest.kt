package com.letta.mobile.appservercli

import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.letta.mobile.appserver.AppServerServeSpec
import com.letta.mobile.appserver.AppServerServeSpecException
import com.letta.mobile.appserver.buildAppServerServeCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Wiring check that `:appserver-cli` resolves the shared serve-spec builder
 * (audit P1.6). Comprehensive argument/validation coverage lives in
 * `:sharedLogic`'s `AppServerServeSpecTest`.
 */
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
    fun `resolves shared builder for a default loopback command`() {
        val command = buildAppServerServeCommand(AppServerServeSpec())

        assertEquals(
            listOf("letta", "app-server", "--listen", "ws://127.0.0.1:4500"),
            command,
        )
    }

    @Test
    fun `non loopback listen requires websocket auth`() {
        assertThrows(AppServerServeSpecException::class.java) {
            buildAppServerServeCommand(AppServerServeSpec(listen = "ws://0.0.0.0:4500"))
        }
    }
}
