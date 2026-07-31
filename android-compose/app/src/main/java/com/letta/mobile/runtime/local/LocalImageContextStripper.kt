package com.letta.mobile.runtime.local

import com.letta.mobile.data.storage.ImageBlobStore
import com.letta.mobile.util.Telemetry
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

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
 * …and BOTH on-disk ROW shapes (letta-mobile-6ppdr):
 *  - session-log v3 envelope (what letta-code 0.29.x actually writes):
 *    { type:"message", id, parentId, timestamp, message:{ role, content:[…] } }
 *  - legacy flat row: { id, role, content:[…] }
 * See [SessionLogEnvelope]. Before that fix this class read `role`/`content` at
 * the TOP level only, so it never once fired on a real 0.29.x transcript.
 *
 * REWRITE SAFETY: this pass REWRITES a file that owns user data. Only the image
 * PARTS inside `message.content` are replaced; the envelope, every other
 * top-level field, every other content part and every unknown/future field are
 * carried through verbatim (see [SessionLogEnvelope.withBody]). Any row that
 * cannot be confidently parsed is passed through UNCHANGED (fail-open per row,
 * telemetered) — never dropped, never re-serialized.
 *
 * Single parse, atomic write, idempotent (a part with empty data / stripped:true
 * is left untouched → no-op once stripped).
 */
class LocalImageContextStripper(
    private val blobStore: ImageBlobStore? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
    /**
     * Per-JSON-string-value cap handed to [BoundedTranscriptReader].
     * Injectable ONLY so tests can trip the cap cheaply (same rationale as
     * `LocalBackendMessageReader.maxTranscriptBytes`): exercising the
     * collapse → targeted-uncapped-re-read path at the real ~8MB default
     * costs tens of MB of heap churn per test, which starves the shared unit-
     * test JVM. Production always uses the default.
     */
    private val maxInlineValueChars: Int = BoundedTranscriptReader.DEFAULT_MAX_INLINE_VALUE_CHARS,
) {
    data class StripReport(val partsStripped: Int, val bytesFreed: Int) {
        val stripped: Boolean get() = partsStripped > 0
    }

    fun stripTranscript(transcript: File): StripReport {
        if (!transcript.isFile) return StripReport(0, 0)
        // Bounded read (letta-mobile-lgns8.20): a single image-bloated row can
        // be tens of MB of base64 (see class doc). Streaming through
        // BoundedTranscriptReader guarantees no single JSON string value is
        // ever materialized beyond its cap, so this pre-turn pass can never
        // OOM on an oversized line the way a plain readLines()/full parse did.
        val snapshotLength = transcript.length()
        val snapshotModified = transcript.lastModified()
        val boundedLines = BoundedTranscriptReader.readLines(transcript, maxInlineValueChars)
        if (boundedLines.isEmpty()) return StripReport(0, 0)
        val lines = boundedLines.map { it.text }

        emitCollapseTelemetry(boundedLines)

        // Pre-parse to locate the most-recent image-bearing user message —
        // that row is preserved so follow-up turns can still reason about the
        // just-posted image (re-port of PR #481 behaviour).
        val rows: List<JsonObject?> = lines.map { line ->
            runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull()
        }
        // If that row's image data was ITSELF bounded/collapsed above (a normal
        // phone photo's base64 easily exceeds the ~8MB per-value cap), do NOT
        // simply skip preservation — that would silently drop the just-shared
        // image from what the model sees (letta-mobile vision regression from
        // PR #1017). Instead, re-read ONLY that one line in full (uncapped);
        // this bounds the extra memory cost to exactly the one image that
        // must survive, while every other row stays subject to the normal
        // collapsing/stripping behavior below.
        val latestImageUserIndex = rows.indexOfLast { row -> row?.isUserImageMessage() == true }
            .let { candidate ->
                if (candidate >= 0 && boundedLines[candidate].collapsedValueChars > 0L) {
                    val fullRow = BoundedTranscriptReader.readSingleLineFull(transcript, candidate)
                        ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                    if (fullRow != null && fullRow.isUserImageMessage()) {
                        emitRereadTelemetry(candidate, recovered = true)
                        candidate
                    } else {
                        emitRereadTelemetry(candidate, recovered = false)
                        // Couldn't recover full data (e.g. file changed
                        // concurrently) — fall back to an earlier,
                        // non-collapsed image row like before.
                        rows.subList(0, candidate).indexOfLast { row -> row?.isUserImageMessage() == true }
                    }
                } else {
                    candidate
                }
            }

        val plan = planRewrites(transcript, boundedLines, rows, latestImageUserIndex)

        if (plan.unparseableRows > 0) {
            Telemetry.event(
                IMAGE_PIPELINE_TAG,
                "strip.unparseable_row_passthrough",
                "rows" to plan.unparseableRows,
                level = Telemetry.Level.WARN,
            )
        }
        val partsStripped = plan.partsStripped
        val bytesFreed = plan.bytesFreed
        if (plan.rewrites.isEmpty()) return StripReport(0, 0)

        // letta-mobile-lgns8.20 (data-loss guard): the embedded letta.js node
        // process OWNS this file and can append to it concurrently while we
        // were reading/rebuilding above (it only loads the store at process
        // start, then writes turns to disk itself). If the file changed
        // underneath us, our rebuild is based on a STALE snapshot — writing it
        // back now would silently clobber whatever letta.js appended in the
        // meantime. Abort instead: skip this pass and let the NEXT pre-turn
        // pass (which will see the now-current file) retry.
        if (transcript.length() != snapshotLength || transcript.lastModified() != snapshotModified) {
            // Boundary telemetry (letta-mobile-iej8j / lgns8.20): the abort
            // that PREVENTS the data loss. Silent aborts made the data-loss
            // window invisible; this makes the guard observable.
            Telemetry.event(
                IMAGE_PIPELINE_TAG,
                "strip.aborted_stale_snapshot",
                "snapshotLength" to snapshotLength,
                "currentLength" to transcript.length(),
                level = Telemetry.Level.WARN,
            )
            return StripReport(0, 0)
        }
        atomicRewrite(transcript, plan.rewrites)
        Telemetry.event(
            IMAGE_PIPELINE_TAG,
            "strip.parts_stripped",
            "parts" to partsStripped,
            "bytesFreed" to bytesFreed,
        )
        return StripReport(partsStripped, bytesFreed)
    }

    /**
     * Any `type:"image"` part that should be replaced with an image_ref or text
     * placeholder — whether it still has base64 data OR is an empty image shell
     * left by an earlier (buggy) strip pass. Empty shells MUST be converted too:
     * an empty image_url is rejected by strict providers (MiniMax 2013).
     *
     * Parts that are already image_ref or text placeholders are NOT images and
     * are skipped, so this stays idempotent (image_ref parts from a prior pass
     * are left untouched).
     */
    private fun isStrippableImage(part: Any?): Boolean {
        val p = part as? JsonObject ?: return false
        // Only real image parts are strippable. A text placeholder (even one
        // carrying an image_ref pointer) is already stripped → idempotent no-op.
        return p["type"]?.jsonStr() == "image"
    }

    private fun imageDataLength(p: JsonObject): Int {
        p["data"]?.jsonStr()?.let { return it.oversizedAwareLength() }
        (p["source"] as? JsonObject)?.get("data")?.jsonStr()?.let { return it.oversizedAwareLength() }
        return 0
    }

    /** Reports the true original length for a value bounded-collapsed by [BoundedTranscriptReader]. */
    private fun String.oversizedAwareLength(): Int =
        BoundedTranscriptReader.extractOversizedLength(this)?.let { it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
            ?: length

    /**
     * Persist image bytes to the blob store and replace the sent image with a
     * TEXT placeholder that CARRIES the blob ref as extra metadata fields.
     *
     * CRITICAL (letta-mobile-xybm2 fix): the embedded letta.js runtime OWNS
     * messages.jsonl and REPLAYS it to the provider on every turn. So whatever
     * part type we leave on disk is what letta.js sends. An {type:"image_ref"}
     * part is unknown to letta.js — its chat/completions image builder reads
     * `item.mimeType` (undefined) and emits `data:undefined;base64,...`, which a
     * strict provider rejects ("image data url media type undefined", code 2013).
     *
     * THEREFORE the on-disk replacement MUST be a plain {type:"text"} part that
     * letta.js can send harmlessly. We attach the rehydration pointer as EXTRA
     * fields (image_ref, mediaType) on that text part: letta.js ignores unknown
     * fields and only forwards `text`, while the mobile timeline resolver reads
     * image_ref to restore the image for the UI. Falls back to a bare text
     * placeholder if the blob store is unavailable or bytes can't be decoded.
     */
    private fun strippedImage(p: JsonObject): JsonObject {
        val mediaType = p["mimeType"]?.jsonStr()
            ?: (p["source"] as? JsonObject)?.get("media_type")?.jsonStr()
            ?: "image"

        // Persist to blob store; embed the ref in the text placeholder's metadata.
        blobStore?.let { store ->
            val imageBytes = extractImageBytes(p)
            if (imageBytes != null) {
                val ref = runCatching { store.putBytes(mediaType, imageBytes) }.getOrNull()
                if (ref != null) {
                    return buildJsonObject {
                        put("type", JsonPrimitive("text"))
                        put("text", JsonPrimitive("[image omitted from context: $mediaType]"))
                        put("stripped", JsonPrimitive(true))
                        // Rehydration pointer — letta.js ignores these extra
                        // fields and only sends `text`; the mobile resolver
                        // reads image_ref to restore the image for the UI.
                        put("image_ref", JsonPrimitive(ref))
                        put("mediaType", JsonPrimitive(mediaType))
                    }
                }
            }
        }

        // Fallback: bare text placeholder (existing behavior, no rehydration).
        return buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive("[image omitted from context: $mediaType]"))
            put("stripped", JsonPrimitive(true))
        }
    }

    /**
     * Extract raw image bytes from either flat or nested image JSON shape.
     * Returns null if base64 data is missing or can't be decoded.
     */
    private fun extractImageBytes(p: JsonObject): ByteArray? {
        val base64Data = p["data"]?.jsonStr()
            ?: (p["source"] as? JsonObject)?.get("data")?.jsonStr()
            ?: return null
        return runCatching {
            Base64.getDecoder().decode(base64Data)
        }.getOrNull()
    }

    /**
     * The rows this pass will rewrite, keyed by their zero-based non-blank line
     * index — plus the running counters. EVERY row not in [rewrites] is copied
     * verbatim from the original file at write time.
     */
    private data class RewritePlan(
        val rewrites: Map<Int, String>,
        val partsStripped: Int,
        val bytesFreed: Int,
        val unparseableRows: Int,
    )

    /**
     * Decide which rows to rewrite, and build their replacement text.
     *
     * DATA-LOSS RULE (PR #1077 review, P1): [BoundedTranscriptReader] replaces
     * EVERY oversized JSON string value with a marker — not only image data. A
     * row's bounded text is therefore a LOSSY view, safe to classify from but
     * never safe to write back. So:
     *
     *  - a row we do not strip is never re-serialized at all (it is stream-copied
     *    from the original bytes by [atomicRewrite]), and
     *  - a row we DO strip is re-read UNCAPPED first when any of its values were
     *    collapsed, so a row carrying both an image and an oversized tool result
     *    keeps that tool result's real content.
     *
     * The uncapped re-read is per-row and sequential, so peak memory stays bounded
     * to one row — the same bound the latest-image re-read already accepted.
     */
    private fun planRewrites(
        transcript: File,
        boundedLines: List<BoundedTranscriptReader.BoundedLine>,
        rows: List<JsonObject?>,
        latestImageUserIndex: Int,
    ): RewritePlan {
        val rewrites = LinkedHashMap<Int, String>()
        var partsStripped = 0
        var bytesFreed = 0
        var unparseableRows = 0

        rows.forEachIndexed { index, boundedRow ->
            if (index == latestImageUserIndex) return@forEachIndexed
            // FAIL-OPEN PER ROW (letta-mobile-6ppdr): a row we cannot parse is
            // never rewritten, so it survives byte-for-byte. It is never dropped
            // and never re-serialized from a partial understanding of its shape.
            if (boundedRow == null) {
                unparseableRows++
                return@forEachIndexed
            }
            // v3 envelope OR legacy flat row — `content` lives on the message
            // BODY, which for a flat row is the row itself.
            val boundedContent = SessionLogEnvelope.body(boundedRow)["content"] as? JsonArray
                ?: return@forEachIndexed
            if (boundedContent.none { isStrippableImage(it) }) return@forEachIndexed

            val sourceRow = if (boundedLines[index].collapsedValueChars > 0L) {
                val fullRow = BoundedTranscriptReader.readSingleLineFull(transcript, index)
                    ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
                if (fullRow == null) {
                    // Original bytes unrecoverable (file changed under us). Leave
                    // the row ALONE rather than persist the bounded markers over
                    // the user's data — skipping a strip is recoverable, the
                    // rewrite is not.
                    emitLossyRewriteSkipped(index)
                    return@forEachIndexed
                }
                fullRow
            } else {
                boundedRow
            }

            val sourceBody = SessionLogEnvelope.body(sourceRow)
            val sourceContent = sourceBody["content"] as? JsonArray ?: return@forEachIndexed
            var strippedHere = 0
            val newContent = buildJsonArray {
                sourceContent.forEach { part ->
                    val p = part as? JsonObject
                    if (p != null && isStrippableImage(p)) {
                        bytesFreed += imageDataLength(p)
                        strippedHere++
                        add(strippedImage(p))
                    } else {
                        add(part)
                    }
                }
            }
            if (strippedHere == 0) return@forEachIndexed
            partsStripped += strippedHere
            // Preserve EVERYTHING except the content array: the body's own other
            // fields (id/role/…/unknown) keep their value and position, and the
            // envelope around it is restored intact.
            val newBodyMap = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>(sourceBody)
            newBodyMap["content"] = newContent
            rewrites[index] = json.encodeToString(
                JsonObject.serializer(),
                SessionLogEnvelope.withBody(sourceRow, JsonObject(newBodyMap)),
            )
        }
        return RewritePlan(rewrites, partsStripped, bytesFreed, unparseableRows)
    }

    /** Boundary telemetry for a strip declined to avoid persisting bounded markers. */
    private fun emitLossyRewriteSkipped(lineIndex: Int) {
        Telemetry.event(
            IMAGE_PIPELINE_TAG,
            "strip.skipped_unrecoverable_row",
            "lineIndex" to lineIndex,
            level = Telemetry.Level.WARN,
        )
    }

    /**
     * Atomically rewrite [target], replacing only the lines in [replacements] and
     * STREAM-COPYING every other line from the original bytes (the precedent set
     * by LocalConversationHealer's byte-level copy of kept lines).
     *
     * Streaming matters twice over: an untouched row is byte-identical to what the
     * runtime wrote — markers from the bounded read can never reach disk — and a
     * multi-MB preserved image row is never materialized as one String.
     */
    private fun atomicRewrite(target: File, replacements: Map<Int, String>) {
        val tmp = target.toPath().resolveSibling("${target.name}.strip.tmp")
        java.nio.channels.FileChannel.open(
            tmp,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
        ).use { channel ->
            val out = java.io.BufferedWriter(
                java.nio.channels.Channels.newWriter(channel, Charsets.UTF_8),
            )
            copyWithReplacements(target, replacements, out)
            out.flush()
            channel.force(true)
        }
        moveIntoPlace(tmp, target)
    }

    /**
     * Copy [source] to [out] verbatim, substituting the replacement text for each
     * non-blank line whose zero-based index appears in [replacements] (the same
     * indexing [BoundedTranscriptReader.readLines] uses).
     */
    private fun copyWithReplacements(source: File, replacements: Map<Int, String>, out: java.io.Writer) {
        source.bufferedReader().use { reader ->
            var nonBlankIndex = -1
            var lineHasContent = false
            var suppress = false
            // Leading whitespace is held only until the line's index is known —
            // bounded by a line's indentation, never by its payload.
            val pendingIndent = StringBuilder()
            val buf = CharArray(16 * 1024)
            var read = reader.read(buf)
            while (read != -1) {
                for (i in 0 until read) {
                    val c = buf[i]
                    if (c == '\n') {
                        if (lineHasContent) {
                            nonBlankIndex++
                            if (suppress) out.write(replacements.getValue(nonBlankIndex))
                        } else if (pendingIndent.isNotEmpty()) {
                            out.write(pendingIndent.toString())
                        }
                        out.write('\n'.code)
                        pendingIndent.setLength(0)
                        lineHasContent = false
                        suppress = false
                        continue
                    }
                    if (!lineHasContent) {
                        if (c.isWhitespace()) {
                            pendingIndent.append(c)
                            continue
                        }
                        lineHasContent = true
                        suppress = replacements.containsKey(nonBlankIndex + 1)
                        if (!suppress) out.write(pendingIndent.toString())
                        pendingIndent.setLength(0)
                    }
                    if (!suppress) out.write(c.code)
                }
                read = reader.read(buf)
            }
            // EOF without a trailing newline: normalize to one, as the previous
            // whole-file write did.
            if (lineHasContent) {
                nonBlankIndex++
                if (suppress) out.write(replacements.getValue(nonBlankIndex))
                out.write('\n'.code)
            } else if (pendingIndent.isNotEmpty()) {
                out.write(pendingIndent.toString())
            }
        }
    }

    /**
     * Swaps the fsynced sidecar in (mirrors BackendOwnershipPreflight /
     * PairedPeerStore's sidecar-write pattern). The `.tmp` was already forced to
     * stable storage by [atomicRewrite], so a crash mid-write leaves either the
     * old transcript or the new one — never a truncated/partial file.
     */
    private fun moveIntoPlace(tmp: java.nio.file.Path, target: File) {
        try {
            java.nio.file.Files.move(
                tmp,
                target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            java.nio.file.Files.move(tmp, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * A user image row = role == "user" AND content array has at least one
     * part with type == "image" (and that image part carries real data —
     * i.e. isStrippableImage returns true).
     */
    private fun JsonObject.isUserImageMessage(): Boolean {
        // Envelope-aware (letta-mobile-6ppdr): role/content live on the message
        // body, which is the row itself only for a legacy flat row.
        val body = SessionLogEnvelope.body(this)
        if (body["role"]?.jsonStr() != "user") return false
        val content = body["content"] as? JsonArray ?: return false
        return content.any { isStrippableImage(it) }
    }

    /**
     * Boundary telemetry (letta-mobile-iej8j): the cap hit is the exact event
     * that silently ate the just-shared image in the #1017 → #1021 regression.
     * Emitting it makes the break observable from telemetry instead of only
     * from a human noticing the model went blind.
     */
    private fun emitCollapseTelemetry(boundedLines: List<BoundedTranscriptReader.BoundedLine>) {
        val collapsedRows = boundedLines.count { it.collapsedValueChars > 0L }
        if (collapsedRows == 0) return
        Telemetry.event(
            IMAGE_PIPELINE_TAG,
            "transcript.value_collapsed",
            "rows" to collapsedRows,
            "chars" to boundedLines.sumOf { it.collapsedValueChars },
            "capChars" to maxInlineValueChars,
            level = Telemetry.Level.WARN,
        )
    }

    /** Boundary telemetry for the #1021 targeted uncapped re-read of the latest image row. */
    private fun emitRereadTelemetry(lineIndex: Int, recovered: Boolean) {
        Telemetry.event(
            IMAGE_PIPELINE_TAG,
            "latest_image.uncapped_reread",
            "lineIndex" to lineIndex,
            "recovered" to recovered,
            level = if (recovered) Telemetry.Level.INFO else Telemetry.Level.WARN,
        )
    }

    private fun kotlinx.serialization.json.JsonElement.jsonStr(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        /**
         * Telemetry tag shared by every image send/receive boundary
         * (letta-mobile-iej8j) — persist → cap → re-read → strip → hydrate.
         * Matches the hydration-side tag in LocalBackendMessageProjection so
         * one filter shows the whole pipe.
         */
        const val IMAGE_PIPELINE_TAG = "ImagePipeline"
    }
}
