package com.letta.mobile.runtime.local

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Heals letta.js's on-device conversation transcript (messages.jsonl) when an
 * interrupted turn leaves a DANGLING tool call — an assistant `toolCall`
 * content part whose `id` has no matching `toolResult` row.
 *
 * Failure mode (the on-device analogue of the shim's lcp-ezv): the embedded
 * node process can die mid-tool (SIGABRT/OOM), or a local turn is cancelled
 * before its tool returns. letta.js persists the assistant message with the
 * `toolCall` part but never writes the `toolResult`. On the NEXT turn letta.js
 * replays messages.jsonl to the provider, and a strict OpenAI/Anthropic API
 * rejects the request — "tool_use ids were found without tool_result blocks" /
 * "tool_call_id without a response" — so every subsequent turn errors out.
 *
 * This is the BELT-AND-SUSPENDERS defense for the local runtime:
 *   1. heal-on-read (defensive backstop): [healTranscript] scans the on-disk
 *      transcript before a turn starts and appends a synthetic interrupted
 *      `toolResult` for every orphaned `toolCall` id, regardless of HOW the
 *      orphan appeared (crash, kill, cancel, bug). Bulletproof.
 *   2. settle-on-interrupt (proactive): the controller calls [healTranscript]
 *      immediately when a local turn ends abnormally, so the store is clean
 *      before it's ever replayed.
 *
 * The transcript schema (pi-ai local-message rows, written by letta.js):
 *  - assistant row: { id, role:"assistant", content:[ {type:"toolCall", id,
 *    name, arguments}, ... ] }
 *  - tool result row: { id, role:"toolResult", toolCallId, isError,
 *    content:[ {type:"text", text} ] }
 *
 * Mutation is append-only (synthetic toolResult rows) + atomic write
 * (temp file + rename) so a crash mid-heal leaves either the old transcript
 * or the new one, never a truncated mix. Idempotent: re-running is a no-op
 * once every toolCall has a matching toolResult.
 */
class LocalConversationHealer(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    data class HealReport(
        val orphanCallIds: List<String>,
        val rowsAppended: Int,
        val orphanResultIds: List<String> = emptyList(),
        val rowsRemoved: Int = 0,
    ) {
        val healed: Boolean get() = rowsAppended > 0 || rowsRemoved > 0
    }

    /**
     * Heals the transcript file in place. Returns a report of what was settled.
     * No-op when the file is missing or already well-formed. Two corruption
     * directions are handled:
     *  1. DANGLING tool CALL — an assistant `toolCall` with no matching
     *     `toolResult`. Settled by appending a synthetic interrupted result.
     *     (Anthropic: "tool_use ids without tool_result".)
     *  2. ORPHANED tool RESULT — a `toolResult` row whose `toolCallId` has NO
     *     preceding `toolCall`. Removed, because a strict OpenAI-shaped provider
     *     rejects it: "Messages with role 'tool' must be a response to a
     *     preceding message with 'tool_calls'" (letta-mobile-5spje). This shows
     *     up on GPT-5.x / OpenAI-compatible providers where the dangling-CALL
     *     direction alone is insufficient.
     */
    fun healTranscript(transcript: File): HealReport {
        if (!transcript.isFile) return HealReport(emptyList(), 0)
        // Snapshot BEFORE the classification read (letta-mobile-lgns8.20):
        // this covers the ENTIRE window from the first byte we read to the
        // final atomic move below, so a concurrent letta.js append/write
        // ANYWHERE in that window — during classification OR during the
        // rewrite — is detected and aborts the pass rather than clobbering
        // the newer on-disk data with decisions made against stale content.
        val snapshotLength = transcript.length()
        val snapshotModified = transcript.lastModified()
        // Bounded classification read (letta-mobile-lgns8.20): an image-bloated
        // row can be tens of MB of base64 (see LocalImageContextStripper).
        // BoundedTranscriptReader guarantees no single JSON string value is
        // ever materialized beyond its cap, so this scan can never OOM on an
        // oversized line. This pass is used ONLY to decide WHAT needs
        // healing (role/type/id fields — never "data") — the actual rewrite
        // below re-streams the file at the byte level so an oversized row's
        // real, uncollapsed bytes are copied through untouched (see
        // [rewriteSelectively]), never corrupted by this bounded read.
        val boundedLines = BoundedTranscriptReader.readLines(transcript)
        if (boundedLines.isEmpty()) return HealReport(emptyList(), 0)
        val lines = boundedLines.map { it.text }

        // Parse each line EXACTLY ONCE. All passes below operate on this single
        // parsed list — a transcript can be megabytes (image-bearing turns), so
        // re-parsing per pass on the turn hot path was a real regression.
        val parsed: List<JsonObject?> = lines.map { line ->
            runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
        }

        // ── Cheap detection pass (no allocation of new lists): decide whether
        //    anything needs healing BEFORE doing any O(n) rewrite of a possibly
        //    huge file. Most turns are well-formed and must pay ~one scan only.
        val declaredCallIds = HashSet<String>()
        val callIdToName = HashMap<String, String>()
        var hasStaleHealRow = false
        for (row in parsed) {
            row ?: continue
            if (row.stringField("role") == "assistant") {
                (row["content"] as? JsonArray)?.forEach { part ->
                    val p = part as? JsonObject ?: return@forEach
                    if (p.stringField("type") == "toolCall") {
                        p.stringField("id")?.let { id ->
                            declaredCallIds.add(id)
                            callIdToName[id] = p.stringField("name") ?: "unknown"
                        }
                    }
                }
            } else if (row.stringField("role") == "toolResult" &&
                row.stringField("id").orEmpty().startsWith("heal-")
            ) {
                hasStaleHealRow = true
            }
        }
        // toolResult ids present, and orphans (no declaring call).
        val resultCallIds = HashSet<String>()
        val orphanResultIds = HashSet<String>()
        for (row in parsed) {
            row ?: continue
            if (row.stringField("role") != "toolResult") continue
            val cid = row.stringField("toolCallId") ?: continue
            resultCallIds.add(cid)
            if (cid !in declaredCallIds) orphanResultIds.add(cid)
        }
        val danglingCallIds = declaredCallIds.filter { it !in resultCallIds }

        if (!hasStaleHealRow && orphanResultIds.isEmpty() && danglingCallIds.isEmpty()) {
            // Well-formed: O(n) scan only, no rewrite. The common fast path.
            return HealReport(emptyList(), 0)
        }

        // ── Repair (only reached when there is real corruption). Decide, BY
        //    LINE INDEX, which original lines to drop and where to insert
        //    synthetic rows — the actual bytes are never rebuilt from the
        //    (possibly bounded) parsed text; see [rewriteSelectively].
        val keptIndices = LinkedHashSet<Int>()
        for (i in parsed.indices) {
            val row = parsed[i]
            if (row == null) {
                keptIndices.add(i)
                continue
            }
            val role = row.stringField("role")
            if (role != "toolResult") {
                keptIndices.add(i)
                continue
            }
            val id = row.stringField("id").orEmpty()
            val cid = row.stringField("toolCallId")
            if (id.startsWith("heal-")) continue // strip stale heal rows
            if (cid != null && cid in orphanResultIds) continue // drop orphan results
            keptIndices.add(i)
        }

        // After stripping, recompute dangling calls (a removed heal/result row
        // re-surfaces its call as dangling → to be re-inserted in position).
        val keptResultIds = HashSet<String>()
        for (i in keptIndices) {
            val row = parsed[i] ?: continue
            if (row.stringField("role") == "toolResult") {
                row.stringField("toolCallId")?.let(keptResultIds::add)
            }
        }
        val finalDangling = declaredCallIds.filter { it !in keptResultIds }
        val callIdToSynthetic: Map<String, JsonObject> = finalDangling.associateWith { callId ->
            syntheticToolResultRow(OrphanToolCall(callId, callIdToName[callId] ?: "unknown"))
        }

        val insertAfterIndex = HashMap<Int, MutableList<JsonObject>>()
        for (i in keptIndices) {
            val row = parsed[i] ?: continue
            if (row.stringField("role") != "assistant") continue
            (row["content"] as? JsonArray)?.forEach { part ->
                val p = part as? JsonObject ?: return@forEach
                if (p.stringField("type") != "toolCall") return@forEach
                val callId = p.stringField("id") ?: return@forEach
                callIdToSynthetic[callId]?.let { synthetic ->
                    insertAfterIndex.getOrPut(i) { mutableListOf() }.add(synthetic)
                }
            }
        }

        val dropCount = lines.size - keptIndices.size
        if (dropCount == 0 && insertAfterIndex.isEmpty()) {
            return HealReport(emptyList(), 0) // nothing actually changes → true no-op
        }

        // Pure "regenerate an identical heal row" no-op detection (idempotency):
        // a previously-synthesized "heal-<callId>" row is UNCONDITIONALLY
        // dropped above so genuinely-stale ones (superseded by a real later
        // result) get cleaned up - but when that callId is STILL dangling,
        // [syntheticToolResultRow] deterministically regenerates the exact
        // same row right back in the exact same position (immediately after
        // its declaring assistant row). That round-trip is invisible on disk,
        // so it must not be reported/written as a change. Every dropped
        // heal- row's (declaring assistant index, callId) is compared against
        // the (assistant index, callId) pairs being reinserted; if they match
        // 1:1 and nothing else was dropped, this is a true no-op.
        var realDrops = 0
        val staleHealDropPairs = HashSet<Pair<Int, String>>()
        for (i in parsed.indices) {
            if (i in keptIndices) continue
            val row = parsed[i] ?: continue
            val id = row.stringField("id").orEmpty()
            if (id.startsWith("heal-")) {
                val callId = row.stringField("toolCallId")
                if (callId != null) {
                    staleHealDropPairs.add((i - 1) to callId) // always inserted right after its assistant row
                    continue
                }
            }
            realDrops++
        }
        val insertedPairs = insertAfterIndex.entries
            .flatMap { (i, rows) -> rows.mapNotNull { row -> row.stringField("toolCallId")?.let { cid -> i to cid } } }
            .toSet()
        if (realDrops == 0 && staleHealDropPairs == insertedPairs) {
            return HealReport(emptyList(), 0) // regenerated heal rows are byte-for-byte identical → true no-op
        }

        // letta-mobile-lgns8.20 (data-loss guard): the embedded letta.js node
        // process OWNS this file and can append to it concurrently while we
        // were scanning above (it only loads the store at process start, then
        // writes turns to disk itself — e.g. it may still be flushing an
        // abnormal-end turn's rows while this post-turn heal runs). If the
        // file changed underneath us, our line-index decisions are based on a
        // STALE snapshot — rewriting now would silently clobber whatever
        // letta.js appended in the meantime. Abort instead: skip this pass
        // and let the next heal (pre-turn, or the next settle-on-interrupt)
        // retry against the now-current file.
        val tmp = transcript.toPath().resolveSibling("${transcript.name}.heal.tmp")
        FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
            .use { channel ->
                // Do NOT `.use {}` this stream - closing it would close the
                // underlying channel before channel.force(true) below runs.
                val out = BufferedOutputStream(Channels.newOutputStream(channel), 64 * 1024)
                rewriteSelectively(transcript, out, dropIndices = parsed.indices.toSet() - keptIndices, insertAfterIndex)
                out.flush()
                channel.force(true)
            }
        if (transcript.length() != snapshotLength || transcript.lastModified() != snapshotModified) {
            Files.deleteIfExists(tmp)
            return HealReport(emptyList(), 0)
        }
        try {
            Files.move(tmp, transcript.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, transcript.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        return HealReport(
            orphanCallIds = finalDangling,
            rowsAppended = callIdToSynthetic.size,
            orphanResultIds = orphanResultIds.toList(),
            rowsRemoved = dropCount,
        )
    }

    /**
     * Re-streams [source] at the BYTE level (never decoding a full line into a
     * Kotlin String), copying every non-blank line whose 0-based index is not
     * in [dropIndices] through to [out] VERBATIM — byte-for-byte, exactly as
     * it exists on disk — then writing any synthetic rows from
     * [insertAfterIndex] immediately after their anchor line. This is what
     * makes the heal safe for an oversized (image-bloated) row: the row is
     * never re-serialized from a bounded/collapsed in-memory representation,
     * so its real content survives untouched even though the DECISION of
     * what to drop/insert was made from a bounded read.
     *
     * Line boundaries and blank-line filtering exactly mirror
     * [BoundedTranscriptReader] (a line "counts" — gets an index — at its
     * first non-whitespace byte; a whitespace-only line is dropped entirely,
     * matching the previous `readLines().filter { it.isNotBlank() }`
     * behaviour) so indices computed from the bounded classification pass
     * line up with indices assigned here.
     */
    private fun rewriteSelectively(
        source: File,
        out: OutputStream,
        dropIndices: Set<Int>,
        insertAfterIndex: Map<Int, List<JsonObject>>,
    ) {
        source.inputStream().buffered(64 * 1024).use { input ->
            var lineIndex = -1
            var sawNonWhitespace = false
            var writingCurrentLine = false
            var pendingWhitespace = java.io.ByteArrayOutputStream()

            fun writeSyntheticRowsFor(index: Int) {
                insertAfterIndex[index]?.forEach { synthetic ->
                    out.write(json.encodeToString(JsonObject.serializer(), synthetic).toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                }
            }

            fun finishLine() {
                if (sawNonWhitespace) {
                    if (writingCurrentLine) out.write('\n'.code)
                    writeSyntheticRowsFor(lineIndex)
                }
                sawNonWhitespace = false
                writingCurrentLine = false
                pendingWhitespace.reset()
            }

            var b = input.read()
            while (b != -1) {
                when (b) {
                    '\n'.code -> finishLine()
                    '\r'.code -> { /* tolerate CRLF: dropped, LF ends the line */ }
                    else -> {
                        val isWhitespace = b == ' '.code || b == '\t'.code
                        if (!sawNonWhitespace) {
                            if (isWhitespace) {
                                pendingWhitespace.write(b)
                            } else {
                                lineIndex++
                                sawNonWhitespace = true
                                writingCurrentLine = lineIndex !in dropIndices
                                if (writingCurrentLine) {
                                    pendingWhitespace.writeTo(out)
                                    out.write(b)
                                }
                                pendingWhitespace.reset()
                            }
                        } else if (writingCurrentLine) {
                            out.write(b)
                        }
                    }
                }
                b = input.read()
            }
            finishLine() // handle a final line with no trailing newline
        }
    }

    internal data class OrphanToolCall(val callId: String, val name: String)

    private fun syntheticToolResultRow(orphan: OrphanToolCall): JsonObject = buildJsonObject {
        put("id", "heal-${orphan.callId}")
        put("role", "toolResult")
        put("toolCallId", orphan.callId)
        put("isError", true)
        put(
            "content",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put(
                            "text",
                            "Tool execution was interrupted (the runtime disconnected or was " +
                                "cancelled before '${orphan.name}' returned). No result is available.",
                        )
                    }
                )
            },
        )
        put(
            "metadata",
            buildJsonObject {
                put("healed", true)
            },
        )
    }

    private fun JsonObject.stringField(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.content
}
