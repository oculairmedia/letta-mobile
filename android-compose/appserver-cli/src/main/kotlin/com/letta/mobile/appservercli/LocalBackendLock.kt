package com.letta.mobile.appservercli

import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Exclusive ownership lock for a Letta local-backend directory
 * (letta-mobile-lgns8.6).
 *
 * The embedded runtime and the JVM App Server supervisor both mutate the
 * on-disk backend under `LETTA_LOCAL_BACKEND_DIR`. Two supervisors owning the
 * same root would corrupt it, so ownership is enforced with an OS-level
 * exclusive [FileLock] on a sentinel file (`.owner.lock`) inside the root. The
 * lock is advisory across processes on the same host but is a true kernel lock,
 * so a second owner — in this or any other process — fails immediately rather
 * than racing.
 *
 * JVM-only by design; kept out of commonMain so shared code stays process/file
 * -channel free.
 */
class LocalBackendLock private constructor(
    private val channel: FileChannel,
    private val lock: FileLock,
    val lockFile: Path,
) : AutoCloseable {

    /** Release ownership. Idempotent. */
    override fun close() {
        try {
            if (lock.isValid) lock.release()
        } catch (_: IOException) {
            // best-effort release; channel close below still frees the handle
        } finally {
            runCatching { channel.close() }
        }
    }

    companion object {
        const val LOCK_FILENAME = ".owner.lock"

        /**
         * Acquire exclusive ownership of [backendDir]. Creates the directory and
         * sentinel file if needed.
         *
         * @throws BackendAlreadyOwnedException if another owner already holds the
         *   lock (same process or another process on this host).
         * @throws IOException on filesystem errors.
         */
        fun acquire(backendDir: String): LocalBackendLock =
            acquire(Paths.get(expandTilde(backendDir)))

        fun acquire(backendDir: Path): LocalBackendLock {
            Files.createDirectories(backendDir)
            val lockFile = backendDir.resolve(LOCK_FILENAME)
            val raf = RandomAccessFile(lockFile.toFile(), "rw")
            val channel = raf.channel
            val lock = try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                // Already held by THIS JVM (another thread/owner).
                runCatching { channel.close() }
                throw BackendAlreadyOwnedException(backendDir, e)
            } catch (e: IOException) {
                runCatching { channel.close() }
                throw e
            }
            if (lock == null) {
                // Held by ANOTHER process on this host.
                runCatching { channel.close() }
                throw BackendAlreadyOwnedException(backendDir, null)
            }
            return LocalBackendLock(channel, lock, lockFile)
        }

        private fun expandTilde(path: String): String {
            if (path == "~") return System.getProperty("user.home")
            if (path.startsWith("~/")) {
                return System.getProperty("user.home") + path.substring(1)
            }
            return path
        }
    }
}

/**
 * Thrown when a backend root is already owned by another App Server supervisor.
 * The caller must fail immediately — never proceed to mutate a foreign-owned
 * backend.
 */
class BackendAlreadyOwnedException(
    backendDir: Path,
    cause: Throwable?,
) : IOException(
    "local backend '$backendDir' is already owned by another App Server process; refusing to start a second owner",
    cause,
)
