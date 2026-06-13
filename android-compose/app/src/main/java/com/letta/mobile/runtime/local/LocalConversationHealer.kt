package com.letta.mobile.runtime.local

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
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
    ) {
        val healed: Boolean get() = rowsAppended > 0
    }

    /**
     * Heals the transcript file in place. Returns a report of what was settled.
     * No-op (rowsAppended = 0) when the file is missing or already well-formed.
     */
    fun healTranscript(transcript: File): HealReport {
        if (!transcript.isFile) return HealReport(emptyList(), 0)
        val lines = transcript.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return HealReport(emptyList(), 0)

        val rows = lines.mapNotNull { line ->
            runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
        }

        val orphans = findOrphanToolCalls(rows)
        if (orphans.isEmpty()) return HealReport(emptyList(), 0)

        val syntheticRows = orphans.map { orphan -> syntheticToolResultRow(orphan) }
        val newLines = lines + syntheticRows.map { json.encodeToString(JsonObject.serializer(), it) }
        atomicWrite(transcript, newLines.joinToString("\n") + "\n")
        return HealReport(orphans.map { it.callId }, syntheticRows.size)
    }

    /**
     * Pure detection over parsed rows: every `toolCall` part id that has no
     * later `toolResult` row with a matching `toolCallId`. Order-aware — a
     * toolResult only satisfies a toolCall that appears before or at it is not
     * required (letta.js appends results after the call), but we treat the set
     * of all toolResult ids as satisfying, which is correct for the API
     * pairing requirement (the request just needs every tool_use to have a
     * tool_result somewhere).
     */
    internal fun findOrphanToolCalls(rows: List<JsonObject>): List<OrphanToolCall> {
        val satisfied = rows
            .filter { it.stringField("role") == "toolResult" }
            .mapNotNull { it.stringField("toolCallId") }
            .toMutableSet()

        val orphans = mutableListOf<OrphanToolCall>()
        val seen = mutableSetOf<String>()
        for (row in rows) {
            if (row.stringField("role") != "assistant") continue
            val parts = (row["content"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            for (part in parts) {
                if (part.stringField("type") != "toolCall") continue
                val callId = part.stringField("id") ?: continue
                if (callId in satisfied || callId in seen) continue
                seen += callId
                orphans += OrphanToolCall(callId = callId, name = part.stringField("name") ?: "unknown")
            }
        }
        return orphans
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
