package com.letta.mobile.data.controller.node.iroh.records

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

@Serializable
private data class WrapperRecordDocument(
    val version: Int,
    val runs: List<StoredRunRecord>,
    val leases: List<StoredLeaseRecord>,
)

/**
 * Single-wrapper JSON persistence: synchronized copy-on-write transactions, fsync and atomic rename.
 * A null path keeps an isolated in-memory store for hosts without durable configuration.
 * [clock] drives startup/write retention and timestamps; [warn] logs corruption without file contents.
 */
class FileWrapperRecordStore(
    private val path: Path? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val warn: () -> Unit = { System.err.println("WARN WrapperRecords store.corrupt") },
) : WrapperRecordStore {
    private val json = LettaJson.persisted
    private var state = load()

    init {
        val retained = prune(state, clock())
        if (retained != state) commit(retained)
    }

    @Synchronized
    override fun record(input: RecordedRunInput): StoredRunRecord {
        val now = clock()
        input.validate(now)
        val retained = prune(state, now)
        val id = runId(input)
        val prior = retained.runs.firstOrNull { it.id == id }
        if (prior != null && prior.input != input) conflict("run identity")
        if (prior != null) {
            commit(retained)
            return prior
        }
        if (retained.runs.size >= MAX_RUNS) capacity("runs")
        val row = StoredRunRecord(input, id, now)
        checkRecordSize(json.encodeToString(row))
        commit(prune(retained.copy(runs = retained.runs + row), now))
        return row
    }

    @Synchronized
    override fun upsert(input: RecordedLeaseInput): StoredLeaseRecord {
        val now = clock()
        input.validate(now)
        val retained = prune(state, now)
        val prior = retained.leases.firstOrNull { it.input.id == input.id }
        if (prior != null && prior.hasDifferentIdentity(input)) {
            conflict("lease ownership/start")
        }
        val others = retained.leases.filterNot { it.input.id == input.id }
        if (others.size >= MAX_LEASES) capacity("leases")
        if (!input.state.closed && others.count { !it.input.state.closed } >= MAX_ACTIVE_LEASES) capacity("active leases")
        val row = StoredLeaseRecord(input, prior?.createdAtMs ?: now, now)
        checkRecordSize(json.encodeToString(row))
        commit(retained.copy(leases = others + row))
        return row
    }

    private fun StoredLeaseRecord.hasDifferentIdentity(input: RecordedLeaseInput): Boolean =
        this.input.owner != input.owner || this.input.startedAtMs != input.startedAtMs

    @Synchronized
    override fun snapshot(): WrapperRecordSnapshot = state

    private fun prune(snapshot: WrapperRecordSnapshot, now: Long) = WrapperRecordSnapshot(
        runs = snapshot.runs.filter { it.input.instantMs >= now - RUN_RETENTION_DAYS * DAY_MS },
        leases = snapshot.leases.filter { !it.input.state.closed || it.updatedAtMs >= now - LEASE_RETENTION_DAYS * DAY_MS },
    )

    private fun runId(input: RecordedRunInput): String {
        val tuple = JsonArray(listOf(
            JsonPrimitive(input.agentId), input.scheduleId?.let(::JsonPrimitive) ?: JsonNull,
            JsonPrimitive(input.scheduleName), JsonPrimitive(input.instantMs), JsonPrimitive(input.source.name.lowercase()),
        ))
        val digest = MessageDigest.getInstance("SHA-256").digest(tuple.toString().toByteArray(Charsets.UTF_8))
        return digest.toHexString()
    }

    private fun checkRecordSize(text: String) {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_RECORD_BYTES) invalid("record size")
    }

    private fun load(): WrapperRecordSnapshot {
        val file = path ?: return WrapperRecordSnapshot()
        if (!Files.exists(file)) return WrapperRecordSnapshot()
        if (!Files.isRegularFile(file)) {
            runCatching { warn() }
            return WrapperRecordSnapshot()
        }
        return runCatching {
            check(Files.size(file) <= MAX_FILE_BYTES)
            val loaded = json.decodeFromString<WrapperRecordDocument>(String(Files.readAllBytes(file), Charsets.UTF_8)).let {
                check(it.version == STORE_VERSION)
                WrapperRecordSnapshot(it.runs, it.leases)
            }
            check(loaded.runs.size <= MAX_RUNS && loaded.leases.size <= MAX_LEASES)
            check(loaded.leases.count { !it.input.state.closed } <= MAX_ACTIVE_LEASES)
            check(loaded.runs.map { it.id }.distinct().size == loaded.runs.size)
            check(loaded.leases.map { it.input.id }.distinct().size == loaded.leases.size)
            loaded.runs.forEach {
                it.input.validate(clock())
                InstantCheck(it.createdAtMs, clock(), "createdAt").validate()
                check(it.id == runId(it.input))
                checkRecordSize(json.encodeToString(it))
            }
            loaded.leases.forEach {
                it.input.validate(clock())
                InstantCheck(it.createdAtMs, clock(), "createdAt").validate()
                InstantCheck(it.updatedAtMs, clock(), "updatedAt").validate()
                checkRecordSize(json.encodeToString(it))
            }
            loaded
        }.getOrElse {
            runCatching { Files.move(file, file.resolveSibling("${file.fileName}.corrupt-${UUID.randomUUID()}")) }
            runCatching { warn() }
            WrapperRecordSnapshot()
        }
    }

    private fun commit(next: WrapperRecordSnapshot) {
        if (next == state) return
        val file = path
        if (file != null) {
            val document = WrapperRecordDocument(STORE_VERSION, next.runs, next.leases)
            persist(file, json.encodeToString(document).toByteArray(Charsets.UTF_8))
        }
        state = next
    }

    private fun persist(file: Path, bytes: ByteArray) {
        if (bytes.size > MAX_FILE_BYTES) capacity("file")
        val absolute = file.toAbsolutePath()
        Files.createDirectories(absolute.parent)
        val temporary = absolute.resolveSibling(".${absolute.fileName}.tmp-${UUID.randomUUID()}")
        try {
            FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            runCatching { FileChannel.open(absolute.parent, StandardOpenOption.READ).use { it.force(true) } }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val STORE_VERSION = 1
        const val RUN_RETENTION_DAYS = 90L
        const val LEASE_RETENTION_DAYS = 30L
        const val MAX_RUNS = 10000
        const val MAX_LEASES = 10000
        const val MAX_ACTIVE_LEASES = 1000
        const val MAX_FILE_BYTES = 64L * 1024 * 1024
    }
}
