package com.letta.mobile.appservercli

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class LocalBackendLockTest {

    @Test
    fun `acquires lock and creates sentinel file`(@TempDir dir: Path) {
        LocalBackendLock.acquire(dir).use { lock ->
            assertTrue(Files.exists(lock.lockFile))
            assertTrue(lock.lockFile.endsWith(LocalBackendLock.LOCK_FILENAME))
        }
    }

    @Test
    fun `second owner of same backend root fails immediately`(@TempDir dir: Path) {
        LocalBackendLock.acquire(dir).use {
            val ex = assertThrows(BackendAlreadyOwnedException::class.java) {
                LocalBackendLock.acquire(dir)
            }
            assertTrue(ex.message!!.contains("already owned"))
        }
    }

    @Test
    fun `lock is reacquirable after release`(@TempDir dir: Path) {
        LocalBackendLock.acquire(dir).close()
        // Must not throw — previous owner released.
        LocalBackendLock.acquire(dir).use {
            assertTrue(Files.exists(it.lockFile))
        }
    }

    @Test
    fun `distinct backend roots do not contend`(@TempDir a: Path, @TempDir b: Path) {
        LocalBackendLock.acquire(a).use {
            // A lock on a different root must succeed concurrently.
            LocalBackendLock.acquire(b).use {
                assertTrue(true)
            }
        }
    }

    @Test
    fun `creates backend directory if missing`(@TempDir dir: Path) {
        val nested = dir.resolve("does/not/exist/yet")
        assertFalse(Files.exists(nested))
        LocalBackendLock.acquire(nested).use {
            assertTrue(Files.isDirectory(nested))
        }
    }

    @Test
    fun `close is idempotent`(@TempDir dir: Path) {
        val lock = LocalBackendLock.acquire(dir)
        lock.close()
        lock.close() // must not throw
        // And the root is now re-lockable.
        LocalBackendLock.acquire(dir).close()
    }
}
