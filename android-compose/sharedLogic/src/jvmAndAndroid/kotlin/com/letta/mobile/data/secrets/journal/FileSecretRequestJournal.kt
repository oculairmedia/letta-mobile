package com.letta.mobile.data.secrets.journal

import com.letta.mobile.data.secrets.request.SecretRequestRecord
import com.letta.mobile.data.hoststate.DurableHostFile
import com.letta.mobile.data.hoststate.HostDirectoryDurability
import com.letta.mobile.data.hoststate.StrictHostJson
import com.letta.mobile.data.serialization.LettaJson
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * A durable [SecretRequestJournal] in one host-owned JSON file, so single use and expiry survive a
 * restart. The directory must be writable only by the host.
 *
 * - Every operation holds an in-process mutex and an OS lock on a `.lock` sibling, then re-reads
 *   the file, so two processes never interleave read-modify-write cycles.
 * - A write is a synced temporary file atomically renamed over the journal, followed by a
 *   directory sync where the platform supports one.
 * - Before replacing the journal a `.pending` barrier is written; it is removed after the
 *   replacement, or after a fully synced rollback when the replacement fails. A barrier left
 *   behind (a crash, or a failed rollback) makes every later operation fail closed with
 *   [SecretJournalProblem.NEEDS_RECONCILIATION] until an operator resolves it.
 * - A journal that is not a regular file, exceeds [MAX_JOURNAL_BYTES], is not strict UTF-8 JSON,
 *   has duplicate keys, an unknown version or records that break [SecretRequestJournalRules]
 *   fails closed with [SecretJournalProblem.CORRUPT]; it is never reset to empty.
 *
 * Only a missing file reads as empty, so deleting the journal forgets every request; keep the
 * directory host-owned.
 */
class FileSecretRequestJournal internal constructor(
    file: Path,
    private val directorySync: (Path) -> Unit,
) : SecretRequestJournal {
    /** A journal at [file] using the host's directory durability barrier where supported. */
    constructor(file: Path) : this(file, HostDirectoryDurability::syncMount)

    private val path: Path = file.toAbsolutePath().normalize()
    private val barrier: Path = path.resolveSibling("${path.fileName}.pending")
    private val journalFile = DurableHostFile(path, directorySync)
    private val barrierFile = DurableHostFile(barrier, directorySync)

    @Volatile
    private var unavailable = false

    /** The stored records; throws [SecretRequestJournalException] when the journal cannot be trusted. */
    override suspend fun read(): List<SecretRequestRecord> = transaction { load().records }

    /** Applies [change], checks the rules and the byte bound, then durably replaces the journal. */
    override suspend fun update(
        change: (List<SecretRequestRecord>) -> List<SecretRequestRecord>,
    ): List<SecretRequestRecord> = transaction {
        val previous = load()
        val next = change(previous.records)
        SecretRequestJournalRules.require(next)
        val encoded = LettaJson.persisted.encodeToString(JournalFile.serializer(), JournalFile(VERSION, next))
        if (encoded.encodeToByteArray().size > MAX_JOURNAL_BYTES) throw SecretRequestJournalException(SecretJournalProblem.FULL)
        replace(encoded, previous.text)
        next
    }

    private suspend fun <T> transaction(action: suspend () -> T): T {
        withContext(Dispatchers.IO) { path.parent?.let(Files::createDirectories) }
        return journalFile.locked {
            val blocked = unavailable || Files.exists(barrier, NOFOLLOW_LINKS)
            if (blocked) throw SecretRequestJournalException(SecretJournalProblem.NEEDS_RECONCILIATION)
            action()
        }
    }

    private fun load(): Loaded {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return Loaded(emptyList(), null)
        return runCatching { decode() }.getOrElse { failure ->
            throw SecretRequestJournalException(SecretJournalProblem.CORRUPT, failure)
        }
    }

    private fun decode(): Loaded {
        check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { NOT_A_FILE }
        check(Files.size(path) in 1..MAX_JOURNAL_BYTES.toLong()) { OUT_OF_BOUNDS }
        val text = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString()
        StrictHostJson.requireUniqueKeys(text)
        val stored = LettaJson.persisted.decodeFromString(JournalFile.serializer(), text)
        check(stored.version == VERSION) { UNKNOWN_VERSION }
        check(SecretRequestJournalRules.problem(stored.records) == null) { BROKEN_RULES }
        return Loaded(stored.records, text)
    }

    private fun replace(next: String, previous: String?) {
        barrierFile.write(BARRIER_TEXT)
        runCatching {
            journalFile.write(next)
            Files.delete(barrier)
        }.onFailure { failure ->
            rollback(previous)
            throw SecretRequestJournalException(SecretJournalProblem.WRITE_FAILED, failure)
        }
    }

    private fun rollback(previous: String?) {
        runCatching {
            if (previous == null) Files.deleteIfExists(path) else journalFile.write(previous)
            Files.delete(barrier)
        }.onFailure { unavailable = true }
    }

    private class Loaded(val records: List<SecretRequestRecord>, val text: String?)

    @Serializable
    private data class JournalFile(val version: Int, val records: List<SecretRequestRecord>)

    /** Format constants. */
    companion object {
        /** The largest journal file, in bytes. */
        const val MAX_JOURNAL_BYTES: Int = 2 * 1024 * 1024

        private const val VERSION = 1
        private const val BARRIER_TEXT = "secret request journal replacement in progress"
        private const val NOT_A_FILE = "not a regular file"
        private const val OUT_OF_BOUNDS = "size out of bounds"
        private const val UNKNOWN_VERSION = "unknown version"
        private const val BROKEN_RULES = "records break the rules"
    }
}
