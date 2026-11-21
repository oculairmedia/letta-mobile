package com.letta.mobile.data.controller.node.iroh.feed

import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStores
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.attr
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The journal's files: one `<conversation hash>-<agent hash>.jsonl` per conversation in [root], plus the `epoch` file.
 * A null [root] is a host without a state directory: every call is a no-op and the journal lives in memory. Every
 * failure is logged and absorbed here (the log goes memory-only, a file that does not decode is deleted); nothing throws.
 * Callers hold the store lock.
 */

/**
 * The file operations the journal performs, in one place so a test can make one of them fail: an owner-only create or
 * replace, an append, a directory sync and a delete. The defaults are the real ones.
 */
internal class FileOps(
    val writeOwnerOnly: (Path, String) -> Unit = IrohSecretKeyStores::writeOwnerOnly,
    val append: (Path, ByteArray) -> Unit = { file, bytes -> Files.write(file, bytes, StandardOpenOption.APPEND) },
    val syncParentOf: (Path) -> Unit = IrohSecretKeyStores::syncParentDirectory,
    val delete: (Path) -> Unit = { file -> Files.deleteIfExists(file) },
)

internal class FeedLogFiles(private val root: Path?, ceilingBytes: Long, private val ops: FileOps = FileOps()) {
    private val ceiling = ByteCount(ceilingBytes)

    /** Bytes of every journal file now, kept current by this class. */
    var diskBytes: ByteCount = NO_BYTES
        private set

    init {
        diskBytes = root?.let { dir -> FeedDirectory.journalFiles(dir).fold(NO_BYTES) { total, file -> total + sizeOf(file) } } ?: NO_BYTES
    }

    fun fileFor(key: JournalKey): Path? = root?.resolve(FeedDirectory.fileName(key))

    /**
     * The log in [key]'s file; null when there is no file, or it did not decode (it is deleted, the conversation starts over),
     * or it stops short of [floor] (a journal whose later entries were only ever kept in memory: it is deleted and the
     * conversation starts after [floor], so no seq is issued twice).
     */
    fun load(key: JournalKey, floor: FeedSeq): FeedLog? {
        val file = fileFor(key)?.takeIf { Files.exists(it) } ?: return null
        return try {
            FeedLogReader.read(String(Files.readAllBytes(file), Charsets.UTF_8), key, file).takeUnless { log ->
                (log.headSeq < floor).also { behind ->
                    if (behind) Telemetry.event(TAG, "log.behind_memory", level = Telemetry.Level.WARN).also { deleteCounting(file) }
                }
            }
        } catch (expected: FeedLogCorrupt) {
            reset(file, "corrupt", expected)
        } catch (expected: IOException) {
            reset(file, "unreadable", expected)
        }
    }

    private fun reset(file: Path, reason: String, failure: Exception): FeedLog? {
        Telemetry.event(TAG, "log.reset", "reason" attr reason, "class" attr failure::class.simpleName, level = Telemetry.Level.WARN)
        deleteCounting(file)
        return null
    }

    /** Appends [line] to [log]'s file, starting the file with its header. */
    fun write(log: FeedLog, line: String) {
        val file = log.file ?: return
        if (log.degraded) return
        val body = (line + "\n").toByteArray(Charsets.UTF_8)
        val header = if (log.fileBytes == NO_BYTES) {
            (FeedLogCodec.header(log.key, log.baseSeq, log.headSeq) + "\n").toByteArray(Charsets.UTF_8)
        } else {
            ByteArray(0)
        }
        try {
            // A journal holds conversation text: a new file is created readable by the host's user alone, appends keep that.
            if (header.isNotEmpty()) createJournal(file, header + body) else ops.append(file, body)
            val written = ByteCount((header.size + body.size).toLong())
            log.fileBytes += written
            diskBytes += written
        } catch (expected: IOException) {
            failedWrite(log, file, expected)
        } catch (expected: IllegalArgumentException) {
            // No way to make the file owner-only (neither POSIX permissions nor an ACL): nothing is written, the log is memory-only.
            failedWrite(log, file, expected)
        }
    }

    /** Creates [file] owner-only with [bytes]; its directory entry is forced to storage too. */
    private fun createJournal(file: Path, bytes: ByteArray) {
        ops.writeOwnerOnly(file, String(bytes, Charsets.UTF_8))
        ops.syncParentOf(file)
    }

    /** A write failed: the log goes memory-only, and the disk total is corrected by whatever part of an append did land. */
    private fun failedWrite(log: FeedLog, file: Path, failure: Exception) {
        degrade(log, failure)
        if (!Files.exists(file)) return
        val actual = sizeOf(file)
        diskBytes = diskBytes - log.fileBytes + actual
        log.fileBytes = actual
    }

    /** Replaces [log]'s file with exactly what the log holds now: drops superseded lines and anything trimmed. */
    fun rewrite(log: FeedLog) {
        val file = log.file ?: return
        if (log.degraded) return
        val text = FeedLogCodec.snapshot(log)
        try {
            createJournal(file, text.toByteArray(Charsets.UTF_8))
            settleAfterRewrite(log, ByteCount(text.toByteArray(Charsets.UTF_8).size.toLong()))
        } catch (expected: IOException) {
            degrade(log, expected)
        } catch (expected: IllegalArgumentException) {
            degrade(log, expected)
        }
    }

    /** The file now holds exactly [size] bytes of live lines: no garbage, no torn tail, every entry written. */
    private fun settleAfterRewrite(log: FeedLog, size: ByteCount) {
        diskBytes = diskBytes - log.fileBytes + size
        log.fileBytes = size
        log.garbageBytes = NO_BYTES
        log.tornTail = false
        log.entries.forEach { it.persisted = true }
    }

    /** Forces [log]'s file to disk: called when a turn ends, not per event. */
    fun sync(log: FeedLog) {
        val file = log.file ?: return
        if (log.degraded || !Files.exists(file)) return
        try {
            FileChannel.open(file, StandardOpenOption.WRITE).use { it.force(true) }
        } catch (expected: IOException) {
            degrade(log, expected)
        }
    }

    private fun degrade(log: FeedLog, failure: Exception) {
        log.degraded = true
        Telemetry.event(TAG, "log.degraded", "class" attr failure::class.simpleName, level = Telemetry.Level.WARN)
    }

    /**
     * Deletes least recently used journal files, oldest first, until the directory is back under the ceiling. A file in
     * [keep] (a conversation with a running turn) is never deleted. Returns the files it deleted.
     */
    fun enforceCeiling(keep: Set<Path>, lastUse: (Path) -> Long, onEvicted: (JournalKey, FeedSeq) -> Unit): List<Path> {
        val dir = root ?: return emptyList()
        if (diskBytes <= ceiling) return emptyList()
        val target = ByteCount(Retention.scaled(ceiling.value))
        // A sequence, so each deletion is seen by the next check: a list would test every file before any is deleted.
        val deleted = FeedDirectory.journalFiles(dir).asSequence()
            .filter { it !in keep }.sortedBy(lastUse).takeWhile { diskBytes > target }
            .filter { file -> evict(file, onEvicted) }
            .toList()
        if (deleted.isNotEmpty()) Telemetry.event(TAG, "disk.evicted", "files" attr deleted.size, "bytes" attr diskBytes.value)
        return deleted
    }

    /** Deletes [file], first telling [onEvicted] which journal it held and how far it had issued. True when it is gone. */
    private fun evict(file: Path, onEvicted: (JournalKey, FeedSeq) -> Unit): Boolean {
        val held = runCatching { String(Files.readAllBytes(file), Charsets.UTF_8) }.getOrNull()?.let(FeedLogCodec::headOf)
        val gone = deleteCounting(file)
        if (gone && held != null) onEvicted(held.first, held.second)
        return gone
    }

    /** Deletes [file]; its bytes leave the disk total only when it is really gone. False when it could not be deleted. */
    private fun deleteCounting(file: Path): Boolean {
        val size = sizeOf(file)
        return try {
            ops.delete(file)
            diskBytes -= size
            true
        } catch (expected: IOException) {
            Telemetry.event(TAG, "delete.failed", "class" attr expected::class.simpleName, level = Telemetry.Level.WARN)
            false
        }
    }

    private fun sizeOf(file: Path): ByteCount = ByteCount(runCatching { Files.size(file) }.getOrDefault(0))

    companion object {
        private const val TAG = "FeedJournal"

        /** The directory to keep journals in, created; null (memory only, logged) when it cannot be. */
        fun prepare(directory: Path?): Path? = directory?.let {
            try {
                Files.createDirectories(it).also { dir -> IrohSecretKeyStores.syncParentDirectory(dir) }
            } catch (expected: IOException) {
                Telemetry.event(TAG, "directory.unavailable", "class" attr expected::class.simpleName, level = Telemetry.Level.WARN)
                null
            }
        }
    }
}
