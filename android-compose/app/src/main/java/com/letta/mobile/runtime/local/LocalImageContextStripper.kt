package com.letta.mobile.runtime.local

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Removes the heavy base64 `data` from image content parts already persisted in
 * the embedded runtime's `messages.jsonl`, replacing it with a small
 * placeholder + `stripped: true` marker.
 *
 * WHY (letta-mobile-87itk): when a user sends an image on the local runtime,
 * letta.js persists the FULL base64 (~500KB+ per image) into the conversation
 * transcript. Every subsequent turn then re-reads, re-parses, re-serializes and
 * RE-SENDS all those images to the provider (which also re-processes the
 * expensive vision tokens) — so turns get progressively much slower as images
 * accumulate (measured: one conversation at 470 rows / 8MB, 90% of it image
 * base64 across 16 images). The image only needs its full data on the ONE turn
 * it is attached; persisted history should carry only a placeholder.
 *
 * SAFE TIMING: call on the PRE-TURN pass. By the time a turn starts, every image
 * already on disk was sent on a PRIOR turn (its job is done). The current turn's
 * new image rides in the incoming wire line and is NOT yet on disk, so it is
 * never stripped in-flight; it lands on disk during the turn and is stripped on
 * the NEXT pre-turn pass.
 *
 * Handles BOTH on-disk image shapes:
 *  - flat:   { type:"image", mimeType, data:"<base64>" }
 *  - nested: { type:"image", source:{ type:"base64", media_type, data:"<base64>" } }
 *
 * Single parse, atomic write, idempotent (a part with empty data / stripped:true
 * is left untouched → no-op once stripped).
 */
class LocalImageContextStripper(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    data class StripReport(val partsStripped: Int, val bytesFreed: Int) {
        val stripped: Boolean get() = partsStripped > 0
    }

    fun stripTranscript(transcript: File): StripReport {
        if (!transcript.isFile) return StripReport(0, 0)
        val lines = transcript.readLines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return StripReport(0, 0)

        // Pre-parse to locate the most-recent image-bearing user message —
        // that row is preserved so follow-up turns can still reason about the
        // just-posted image (re-port of PR #481 behaviour).
        val rows: List<JsonObject?> = lines.map { line ->
            runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
        }
        val latestImageUserIndex = rows.indexOfLast { row -> row?.isUserImageMessage() == true }

        var partsStripped = 0
        var bytesFreed = 0
        var changed = false

        val rebuilt = lines.mapIndexed { index, line ->
            if (index == latestImageUserIndex) return@mapIndexed line

            val row = rows.getOrNull(index) ?: return@mapIndexed line
            val content = row["content"] as? JsonArray ?: return@mapIndexed line
            if (content.none { isStrippableImage(it) }) return@mapIndexed line

            val newContent = buildJsonArray {
                content.forEach { part ->
                    val p = part as? JsonObject
                    if (p != null && isStrippableImage(p)) {
                        bytesFreed += imageDataLength(p)
                        partsStripped += 1
                        changed = true
                        add(strippedImage(p))
                    } else {
                        add(part)
                    }
                }
            }
            val newMap = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(row)
            newMap["content"] = newContent
            json.encodeToString(JsonObject.serializer(), JsonObject(newMap))
        }

        if (!changed) return StripReport(0, 0)
        atomicWrite(transcript, rebuilt.joinToString("\n") + "\n")
        return StripReport(partsStripped, bytesFreed)
    }

    /**
     * Any `type:"image"` part that should be replaced with a text placeholder —
     * whether it still has base64 data OR is an empty image shell left by an
     * earlier (buggy) strip pass. Empty shells MUST be converted too: an empty
     * image_url is rejected by strict providers (MiniMax 2013). A part that is
     * already a text placeholder (type:text) is not an image and is skipped, so
     * this stays idempotent.
     */
    private fun isStrippableImage(part: Any?): Boolean {
        val p = part as? JsonObject ?: return false
        return p["type"]?.jsonStr() == "image"
    }

    private fun imageDataLength(p: JsonObject): Int {
        p["data"]?.jsonStr()?.let { return it.length }
        (p["source"] as? JsonObject)?.get("data")?.jsonStr()?.let { return it.length }
        return 0
    }

    /**
     * Replace a sent image with a TEXT placeholder part — NOT an empty
     * `{type:image}`. An empty image part is still serialized to the provider
     * as an image_url with empty base64, which a strict provider rejects with
     * "invalid image content: decode image config: unknown format" (MiniMax
     * code 2013). A text placeholder keeps the context small, leaves a
     * human/agent-readable trace that an image was here, and never reaches the
     * provider as a malformed image.
     */
    private fun strippedImage(p: JsonObject): JsonObject {
        val mediaType = p["mimeType"]?.jsonStr()
            ?: (p["source"] as? JsonObject)?.get("media_type")?.jsonStr()
            ?: "image"
        return buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive("[image omitted from context: $mediaType]"))
            put("stripped", JsonPrimitive(true))
        }
    }

    private fun atomicWrite(target: File, contents: String) {
        val tmp = File(target.parentFile, "${target.name}.strip.tmp")
        tmp.writeText(contents)
        if (!tmp.renameTo(target)) {
            target.writeText(contents)
            tmp.delete()
        }
    }

    /**
     * A user image row = role == "user" AND content array has at least one
     * part with type == "image" (and that image part carries real data —
     * i.e. isStrippableImage returns true).
     */
    private fun JsonObject.isUserImageMessage(): Boolean {
        if (this["role"]?.jsonStr() != "user") return false
        val content = this["content"] as? JsonArray ?: return false
        return content.any { isStrippableImage(it) }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonStr(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
