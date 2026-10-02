package com.letta.mobile.desktop.data

import com.letta.mobile.data.timeline.snapshot.StoredImageBodyReference
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Content-addressed image bodies kept beside a scope's timeline data on Desktop.
 *
 * Same rules as Android's Room ledger (RoomTimelineBoundedStore): one body per SHA-256 of the
 * decoded bytes, canonical base64 only, at most [MAX_IMAGE_BYTES], and a reference resolves only
 * when its provenance, decoded size and hash all match what is on disk. Callers do blocking IO, so
 * they must already be on an IO dispatcher.
 */
internal object DesktopTimelineImageBodies {
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val PROVENANCE = "canonical-inline-v1"
    const val DIRECTORY_SUFFIX = ".images"
    private const val BODY_SUFFIX = ".bin"
    private const val STAGING_PREFIX = "body-"
    private const val STAGING_SUFFIX = ".tmp"
    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    class Decoded(val bytes: ByteArray, val reference: StoredImageBodyReference)

    fun decode(base64: String): Decoded {
        require(base64.length <= ((MAX_IMAGE_BYTES + 2) / 3) * 4) { "Image exceeds budget" }
        val bytes = Base64.getDecoder().decode(base64)
        require(bytes.size in 1..MAX_IMAGE_BYTES)
        require(Base64.getEncoder().encodeToString(bytes) == base64) { "Non-canonical image base64" }
        return Decoded(bytes, StoredImageBodyReference(sha256Hex(bytes), bytes.size.toLong()))
    }

    fun bodyFile(directory: Path, sha256: String): Path = directory.resolve(sha256 + BODY_SUFFIX)

    /** Null when the body is absent, truncated, or not the content [reference] names. */
    suspend fun read(file: Path, reference: StoredImageBodyReference): String? {
        currentCoroutineContext().ensureActive()
        val bytes = load(file, reference) ?: return null
        currentCoroutineContext().ensureActive()
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** Whether [file] already holds exactly the body [reference] names. */
    fun holds(file: Path, reference: StoredImageBodyReference): Boolean = load(file, reference) != null

    private fun load(file: Path, reference: StoredImageBodyReference): ByteArray? {
        if (reference.provenance != PROVENANCE ||
            reference.decodedBytes !in 1..MAX_IMAGE_BYTES.toLong() ||
            !reference.sha256.matches(SHA256_HEX)
        ) return null
        val bytes = try {
            if (!Files.isRegularFile(file) || Files.size(file) != reference.decodedBytes) return null
            Files.readAllBytes(file)
        } catch (_: IOException) {
            // Deleted between the size check and the read: the same as never stored.
            return null
        }
        return bytes.takeIf { it.size.toLong() == reference.decodedBytes && sha256Hex(it) == reference.sha256 }
    }

    /** Writes [bytes] durably under a temporary name in [directory]; [publish] makes them visible. */
    fun stage(directory: Path, bytes: ByteArray): Path {
        Files.createDirectories(directory)
        val staged = Files.createTempFile(directory, STAGING_PREFIX, STAGING_SUFFIX)
        var written = false
        try {
            FileChannel.open(staged, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            written = true
        } finally {
            if (!written) Files.deleteIfExists(staged)
        }
        return staged
    }

    fun publish(staged: Path, target: Path) {
        try {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Marks a deduplicated body as freshly used so a concurrent [sweep] keeps it. */
    fun touch(file: Path) {
        try {
            Files.setLastModifiedTime(file, FileTime.from(Instant.now()))
        } catch (_: IOException) {
            // A body that vanished here is re-checked by the reader; nothing to keep alive.
        }
    }

    /**
     * Deletes bodies no longer named by [referenced], and abandoned staging files. Anything modified
     * after [cutoff] is kept: a body is written before the snapshot that names it, so a fresh
     * unreferenced body may belong to the next write.
     */
    fun sweep(directory: Path, referenced: Set<String>, cutoff: Instant): Int {
        if (!Files.isDirectory(directory)) return 0
        var deleted = 0
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) {
                val name = entry.fileName.toString()
                val reclaimable = when {
                    name.endsWith(BODY_SUFFIX) -> name.removeSuffix(BODY_SUFFIX) !in referenced
                    name.startsWith(STAGING_PREFIX) && name.endsWith(STAGING_SUFFIX) -> true
                    else -> false
                }
                if (reclaimable && olderThan(entry, cutoff) && Files.deleteIfExists(entry)) deleted++
            }
        }
        return deleted
    }

    fun deleteDirectory(directory: Path) {
        if (!Files.exists(directory)) return
        Files.walk(directory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).toList().forEach { Files.deleteIfExists(it) }
        }
    }

    private fun olderThan(file: Path, cutoff: Instant): Boolean = try {
        Files.getLastModifiedTime(file).toInstant().isBefore(cutoff)
    } catch (_: IOException) {
        false
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
}
