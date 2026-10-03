package com.letta.mobile.data.controller.node.iroh

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
 * line for the same row wins. [load] serves only the newest [maxEntries] lines and rewrites a file
 * that has grown past twice that. Compaction runs once per load, so a long-running host's file
 * grows with its turn count between restarts. A line that is not a version 1 entry is skipped and
 * reported as `turnIdentity.storeCorruptLine`.
 */
class FileTurnIdentityStore(
    private val directory: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : TurnIdentityStore {
    override suspend fun load(conversationId: String): Map<StoredRowRef, RowIdentity> = withContext(Dispatchers.IO) {
        val file = fileFor(conversationId)
        if (!file.isFile) return@withContext emptyMap()
        val lines = file.readLines().filter { it.isNotBlank() }
        val newest = lines.takeLast(maxEntries).mapNotNull { parseLine(conversationId, it) }
        if (lines.size > maxEntries * 2) rewrite(file, newest)
        LinkedHashMap<StoredRowRef, RowIdentity>().also { map ->
            newest.forEach { (ref, identity) ->
                map.remove(ref)
                map[ref] = identity
            }
        }
    }

    override suspend fun append(conversationId: String, entries: Map<StoredRowRef, RowIdentity>) {
        if (entries.isEmpty()) return
        withContext(Dispatchers.IO) {
            directory.mkdirs()
            fileFor(conversationId).appendText(entries.entries.joinToString("") { (ref, identity) -> line(ref, identity) + "\n" })
        }
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

    private fun parseLine(conversationId: String, line: String): Pair<StoredRowRef, RowIdentity>? = runCatching {
        val obj: JsonObject = Json.parseToJsonElement(line).jsonObject
        check(obj["v"]?.jsonPrimitive?.intOrNull == VERSION)
        fun field(key: String) = checkNotNull(obj[key]?.jsonPrimitive?.contentOrNull)
        StoredRowRef(field("d"), field("t")) to RowIdentity(field("l"), field("n"))
    }.onFailure {
        Telemetry.event(
            "TurnIdentity", "turnIdentity.storeCorruptLine",
            "conversationId" to conversationId,
            "preview" to line.take(CORRUPT_PREVIEW_CHARS),
        )
    }.getOrNull()

    companion object {
        const val DEFAULT_MAX_ENTRIES = 5_000
        private const val VERSION = 1
        private const val CORRUPT_PREVIEW_CHARS = 80
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")

        /** The host's state directory for the ledger: beside the model-exposure file the host already keeps. */
        fun inHostState(hostStateDir: File): FileTurnIdentityStore = FileTurnIdentityStore(File(hostStateDir, "turn-identity"))
    }
}
