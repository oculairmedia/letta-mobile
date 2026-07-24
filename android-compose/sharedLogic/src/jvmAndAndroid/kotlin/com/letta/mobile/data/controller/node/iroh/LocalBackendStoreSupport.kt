package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * lgns8.9: shared low-level primitives for the on-disk LocalBackend admin-query
 * readers. Holds the single [json] instance, base64url key codec, timestamp
 * helpers, and the sidecar readers (real-times / string-map / attachment-map)
 * used across more than one reader. Split out of the former monolithic
 * `LocalBackendAdminStore` as pure code motion — no behavior change.
 */
internal class LocalBackendStoreSupport(
    val baseDir: File,
    val lmstudioBaseUrl: String,
) {
    val json = Json { ignoreUnknownKeys = true }

    fun conversationKey(conversationId: String, agentId: String): String =
        if (conversationId == "default") "default:$agentId" else "conversation:$conversationId"

    fun b64UrlEncode(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    fun b64UrlDecode(value: String): String =
        String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)

    fun isSentinelDate(iso: String): Boolean = iso.startsWith("2026-01-01T")

    /** JS `new Date(ms).toISOString()` — always UTC, always 3-digit millis, 'Z'. */
    fun isoMillis(ms: Long): String =
        ISO_MILLIS.format(Instant.ofEpochMilli(ms).atOffset(ZoneOffset.UTC))

    /**
     * Port of store.ts readMessageTimestamps: legacy `_real-times.json` map
     * first, then overlay `_real-times.jsonl` (later lines win). Returns the
     * full id->iso map (the projection joins it per message; conversation.list
     * only needs the max, which [maxRealMessageTime] keeps separate).
     */
    fun readRealTimesMap(dir: File): Map<String, String> {
        val map = HashMap<String, String>()
        runCatching {
            File(dir, "_real-times.json").takeIf { it.isFile }?.readText()?.let { json.parseToJsonElement(it).jsonObject }
                ?.forEach { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { map[k] = it.content } }
        }
        runCatching {
            File(dir, "_real-times.jsonl").takeIf { it.isFile }?.forEachLine { line ->
                val t = line.trim()
                if (t.isEmpty()) return@forEachLine
                runCatching {
                    val o = json.parseToJsonElement(t).jsonObject
                    val id = o["id"]?.jsonPrimitive?.contentOrNullSafe()
                    val iso = o["iso"]?.jsonPrimitive?.contentOrNullSafe()
                    if (id != null && iso != null) map[id] = iso
                }
            }
        }
        return map
    }

    /** Read a flat string->string JSON map (e.g. `_otid-map.json`); {} on any fault. */
    fun readStringMap(file: File): Map<String, String> {
        val obj = runCatching {
            file.takeIf { it.isFile }?.readText()?.let { json.parseToJsonElement(it).jsonObject }
        }.getOrNull() ?: return emptyMap()
        val map = HashMap<String, String>()
        obj.forEach { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { map[k] = it.content } }
        return map
    }

    /** Read `_attachments.json`: id -> JsonArray of attachment refs; {} on any fault. */
    fun readAttachmentMap(file: File): Map<String, JsonArray> {
        val obj = runCatching {
            file.takeIf { it.isFile }?.readText()?.let { json.parseToJsonElement(it).jsonObject }
        }.getOrNull() ?: return emptyMap()
        val map = HashMap<String, JsonArray>()
        obj.forEach { (k, v) -> (v as? JsonArray)?.let { map[k] = it } }
        return map
    }

    /** Port of maxRealMessageTime: legacy _real-times.json map, then overlay _real-times.jsonl (later wins), max iso. */
    fun maxRealMessageTime(conversationId: String, agentId: String): String {
        val dir = File(File(baseDir, "conversations"), b64UrlEncode(conversationKey(conversationId, agentId)))
        val map = HashMap<String, String>()
        runCatching {
            File(dir, "_real-times.json").takeIf { it.isFile }?.readText()?.let { json.parseToJsonElement(it).jsonObject }
                ?.forEach { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.let { map[k] = it.content } }
        }
        runCatching {
            File(dir, "_real-times.jsonl").takeIf { it.isFile }?.forEachLine { line ->
                val t = line.trim()
                if (t.isEmpty()) return@forEachLine
                runCatching {
                    val o = json.parseToJsonElement(t).jsonObject
                    val id = o["id"]?.jsonPrimitive?.contentOrNullSafe()
                    val iso = o["iso"]?.jsonPrimitive?.contentOrNullSafe()
                    if (id != null && iso != null) map[id] = iso
                }
            }
        }
        var max = ""
        for (iso in map.values) if (iso > max) max = iso
        return max
    }

    companion object {
        private val ISO_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    }
}

// ── shared pure helpers (package-internal, used by every reader) ──────────

internal fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonElement.longOrNull(): Long? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toLong()

internal fun JsonElement.doubleOrNull(): Double? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()

internal fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

internal inline fun JsonElement.nonNullOr(fallback: () -> JsonElement): JsonElement =
    if (this is JsonNull) fallback() else this

internal fun sha256Hex(input: String): String =
    MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
