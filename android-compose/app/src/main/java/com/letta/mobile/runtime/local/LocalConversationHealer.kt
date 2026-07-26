package com.letta.mobile.runtime.local

import java.io.File
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
        val lines = transcript.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return HealReport(emptyList(), 0)

        // Parse each line EXACTLY ONCE. All passes below operate on this single
        // parsed list — a transcript can be megabytes (image-bearing turns), so
        // re-parsing per pass on the turn hot path was a real regression.
        val parsed: List<Pair<String, JsonObject?>> = lines.map { line ->
            line to runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
        }

        // ── Cheap detection pass (no allocation of new lists): decide whether
        //    anything needs healing BEFORE doing any O(n) rewrite of a possibly
        //    huge file. Most turns are well-formed and must pay ~one scan only.
        val declaredCallIds = HashSet<String>()
        val callIdToName = HashMap<String, String>()
        var hasStaleHealRow = false
        for ((_, row) in parsed) {
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
        for ((_, row) in parsed) {
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

        // ── Repair (only reached when there is real corruption). Build the
        //    output in one pass over the already-parsed rows.
        // Strip stale "heal-" rows + truly-orphaned results; recompute which
        // calls become dangling once a stale heal row is removed.
        val keptParsed = parsed.filter { (_, row) ->
            row ?: return@filter true
            val role = row.stringField("role")
            if (role != "toolResult") return@filter true
            val id = row.stringField("id").orEmpty()
            val cid = row.stringField("toolCallId")
            if (id.startsWith("heal-")) return@filter false      // strip stale heal rows
            if (cid != null && cid in orphanResultIds) return@filter false // drop orphan results
            true
        }
        // After stripping, recompute dangling calls (a removed heal/result row
        // re-surfaces its call as dangling → to be re-inserted in position).
        val keptResultIds = HashSet<String>()
        for ((_, row) in keptParsed) {
            if (row?.stringField("role") == "toolResult") {
                row.stringField("toolCallId")?.let(keptResultIds::add)
            }
        }
        val finalDangling = declaredCallIds.filter { it !in keptResultIds }
        val callIdToSynthetic: Map<String, JsonObject> = finalDangling.associateWith { callId ->
            syntheticToolResultRow(OrphanToolCall(callId, callIdToName[callId] ?: "unknown"))
        }

        val rebuilt = ArrayList<String>(keptParsed.size + callIdToSynthetic.size)
        for ((line, row) in keptParsed) {
            rebuilt.add(line)
            if (row?.stringField("role") != "assistant") continue
            (row["content"] as? JsonArray)?.forEach { part ->
                val p = part as? JsonObject ?: return@forEach
                if (p.stringField("type") != "toolCall") return@forEach
                val callId = p.stringField("id") ?: return@forEach
                callIdToSynthetic[callId]?.let { rebuilt.add(json.encodeToString(JsonObject.serializer(), it)) }
            }
        }

        val newContent = rebuilt.joinToString("\n") + "\n"
        if (newContent == lines.joinToString("\n") + "\n") {
            return HealReport(emptyList(), 0) // byte-identical → true no-op
        }

        atomicWrite(transcript, newContent)
        return HealReport(
            orphanCallIds = finalDangling,
            rowsAppended = callIdToSynthetic.size,
            orphanResultIds = orphanResultIds.toList(),
            rowsRemoved = lines.size - keptParsed.size,
        )
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

    private fun atomicWrite(target: File, contents: String) {
        val tmp = File(target.parentFile, "${target.name}.heal.tmp")
        tmp.writeText(contents)
        if (!tmp.renameTo(target)) {
            // renameTo can fail across some filesystems; fall back to copy+delete.
            target.writeText(contents)
            tmp.delete()
        }
    }

    private fun JsonObject.stringField(key: String): String? =
        this[key]?.jsonPrimitive?.takeIf { it.isString }?.content
}
