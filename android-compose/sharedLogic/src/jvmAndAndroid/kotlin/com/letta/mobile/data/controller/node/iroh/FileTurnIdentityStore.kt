package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.util.attr
import com.letta.mobile.data.runtime.RowIdentity
import com.letta.mobile.data.runtime.StoredRowRef
import com.letta.mobile.data.runtime.TurnIdentityStore
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * Append-only `<stateDir>/turn-identity/<conversationId>.jsonl`, one entry per line:
 * `{"v":1,"d":"<durable id>","t":"<message_type>","l":"<logical id>","n":"<turn id>"}`. A later
 * line for the same row wins. [load] serves only the newest [maxEntries] lines, holding no more
 * than that many in memory, and rewrites a file that has grown past twice that. [append] rewrites a
 * file to its newest [maxEntries] lines once it passes [maxFileBytes], so a long-running host's
 * files stay bounded between restarts. When an append starts a new file and the directory holds
 * more than [maxFiles] ledgers, the least recently written are deleted and reported as
 * `turnIdentity.filesPruned`; those conversations fall back to the identities in their rows. A
 * line that is not a version 1 entry is skipped and reported as `turnIdentity.storeCorruptLine` under the file's name.
 */
class FileTurnIdentityStore(
    private val directory: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
) : TurnIdentityStore {
    init {
        require(maxEntries > 0 && maxFiles > 0) { "maxEntries and maxFiles must be positive" }
    }

    // Serializes rewrites against appends so a compaction never drops a line appended meanwhile.
    private val lock = Any()

    override suspend fun load(conversationId: String): Map<StoredRowRef, RowIdentity> = withContext(Dispatchers.IO) {
        val file = fileFor(conversationId)
        synchronized(lock) {
            if (!file.isFile) return@withContext emptyMap()
            val newest = newestEntries(file, compactPast = maxEntries * 2)
            LinkedHashMap<StoredRowRef, RowIdentity>().also { map ->
                newest.forEach { (ref, identity) ->
                    map.remove(ref)
                    map[ref] = identity
                }
            }
        }
    }

    override suspend fun append(conversationId: String, entries: Map<StoredRowRef, RowIdentity>) {
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                directory.mkdirs()
                val file = fileFor(conversationId)
                val isNew = !file.exists()
                file.appendText(entries.entries.joinToString("") { (ref, identity) -> line(ref, identity) + "\n" })
                if (file.length() > maxFileBytes) newestEntries(file, compactPast = maxEntries)
                if (isNew) pruneOldestFiles(keep = file)
            }
        }
    }

    /** The newest [maxEntries] parsed lines; rewrites [file] to them when it has more than [compactPast] lines. */
    private fun newestEntries(file: File, compactPast: Int): List<Pair<StoredRowRef, RowIdentity>> {
        val tail = ArrayDeque<String>(maxEntries)
        var lineCount = 0
        file.useLines { lines ->
            lines.filter { it.isNotBlank() }.forEach { line ->
                lineCount++
                if (tail.size == maxEntries) tail.removeFirst()
                tail.addLast(line)
            }
        }
        val newest = tail.mapNotNull { parseLine(file, it) }
        if (lineCount > compactPast) rewrite(file, newest)
        return newest
    }

    /** Deletes the least recently written ledgers past [maxFiles], never [keep] (the one just started). */
    private fun pruneOldestFiles(keep: File) {
        val files = directory.listFiles { file -> file.isFile && file.name.endsWith(".jsonl") } ?: return
        if (files.size <= maxFiles) return
        val others = files.filter { it != keep }.sortedByDescending { it.lastModified() }
        val pruned = others.drop(maxFiles - 1).count { it.delete() }
        if (pruned == 0) return
        Telemetry.event(
            "TurnIdentity", "turnIdentity.filesPruned",
            "pruned" attr pruned, "maxFiles" attr maxFiles,
            level = Telemetry.Level.WARN,
        )
    }

    private fun rewrite(file: File, entries: List<Pair<StoredRowRef, RowIdentity>>) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(entries.joinToString("") { (ref, identity) -> line(ref, identity) + "\n" })
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun fileFor(conversationId: String): File =
        File(directory, conversationId.replace(UNSAFE, "_") + ".jsonl")

    private fun line(ref: StoredRowRef, identity: RowIdentity): String = buildJsonObject {
        put("v", VERSION)
        put("d", ref.durableId)
        put("t", ref.messageType)
        put("l", identity.logicalMessageId)
        put("n", identity.turnId)
    }.toString()

    private fun parseLine(ledger: File, line: String): Pair<StoredRowRef, RowIdentity>? = runCatching {
        val obj: JsonObject = Json.parseToJsonElement(line).jsonObject
        check(obj["v"]?.jsonPrimitive?.intOrNull == VERSION)
        fun field(key: String) = checkNotNull(obj[key]?.jsonPrimitive?.contentOrNull)
        StoredRowRef(field("d"), field("t")) to RowIdentity(field("l"), field("n"))
    }.onFailure {
        Telemetry.event(
            "TurnIdentity", "turnIdentity.storeCorruptLine",
            "conversationId" attr ledger.nameWithoutExtension,
            "preview" attr line.take(CORRUPT_PREVIEW_CHARS),
        )
    }.getOrNull()

    companion object {
        const val DEFAULT_MAX_ENTRIES = 5_000

        /** About four times what [DEFAULT_MAX_ENTRIES] lines take, so appends rarely compact. */
        const val DEFAULT_MAX_FILE_BYTES: Long = 4L * 1024 * 1024

        /** One ledger per conversation the host has served turns for. */
        const val DEFAULT_MAX_FILES = 2_000
        private const val VERSION = 1
        private const val CORRUPT_PREVIEW_CHARS = 80
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")

        /** The host's state directory for the ledger: beside the model-exposure file the host already keeps. */
        fun inHostState(hostStateDir: File): FileTurnIdentityStore = FileTurnIdentityStore(File(hostStateDir, "turn-identity"))
    }
}
