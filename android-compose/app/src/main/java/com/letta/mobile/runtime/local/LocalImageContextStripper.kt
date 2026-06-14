package com.letta.mobile.runtime.local

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

        var partsStripped = 0
        var bytesFreed = 0
        var changed = false

        val rebuilt = lines.map { line ->
            val row = runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
                ?: return@map line
            val content = row["content"] as? JsonArray ?: return@map line
            if (content.none { isStrippableImage(it) }) return@map line

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

    /** A non-empty image part whose data has not already been stripped. */
    private fun isStrippableImage(part: Any?): Boolean {
        val p = part as? JsonObject ?: return false
        if (p["type"]?.jsonStr() != "image") return false
        if (p["stripped"]?.jsonPrimitive?.content == "true") return false
        return imageDataLength(p) > 0
    }

    private fun imageDataLength(p: JsonObject): Int {
        p["data"]?.jsonStr()?.let { return it.length }
        (p["source"] as? JsonObject)?.get("data")?.jsonStr()?.let { return it.length }
        return 0
    }

    private fun strippedImage(p: JsonObject): JsonObject {
        val map = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(p)
        // flat shape
        if (map.containsKey("data")) map["data"] = JsonPrimitive("")
        // nested source shape
        (map["source"] as? JsonObject)?.let { src ->
            val srcMap = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(src)
            srcMap["data"] = JsonPrimitive("")
            map["source"] = JsonObject(srcMap)
        }
        map["stripped"] = JsonPrimitive(true)
        return JsonObject(map)
    }

    private fun atomicWrite(target: File, contents: String) {
        val tmp = File(target.parentFile, "${target.name}.strip.tmp")
        tmp.writeText(contents)
        if (!tmp.renameTo(target)) {
            target.writeText(contents)
            tmp.delete()
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonStr(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
