package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.ToolCallMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.model.UserMessage
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.storage.ImageBlobStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.Base64

/** A persisted pi-ai toolResult row (see [LocalBackendEmbeddedStore.readToolResults]). */
data class StoredToolResult(
    val toolCallId: String,
    val body: String,
    val isError: Boolean,
)

/**
 * letta-mobile-by4vm.21: the embedded runtime's typed projection of a default-conversation
 * `messages.jsonl` (pi-ai local-message rows maintained by letta.js) into timeline messages —
 * moved here from the app's `LettaCodeLocalBackendStore` with its behaviour unchanged.
 *
 * Reads go through [BoundedTranscriptReader] (letta-mobile-lgns8.20), so no single JSON string
 * value is ever materialized beyond its cap; a collapsed image/text value is rendered as a small
 * placeholder instead of dropping the row.
 *
 * Both reads share one row parser ([readMessageRows]): bounded lines, with session-log v3
 * envelopes (`{type:message,message:{…}}`, written by letta-code 0.29.x) unwrapped to their
 * message body via [LocalBackendLayout.messageBody] — the same unwrap [LocalBackendMessageReader]
 * applies (letta-mobile-by4vm.73). [maxInlineValueChars] is the per-value cap (tests lower it).
 */
internal class LocalBackendEmbeddedTranscript(
    private val baseDir: File,
    private val maxInlineValueChars: Int = BoundedTranscriptReader.DEFAULT_MAX_INLINE_VALUE_CHARS,
) {
    // Decode-only reads of the on-disk transcript (letta-mobile-by4vm.20).
    private val json: Json = LettaJson.persisted

    fun readTranscript(agentId: AgentId, blobStoreFor: (File) -> ImageBlobStore): List<LettaMessage> {
        val conversationDir = LocalBackendLayout.defaultConversationDir(baseDir, agentId)
        val transcript = File(conversationDir, LocalBackendLayout.TRANSCRIPT_FILE)
        if (!transcript.isFile) return emptyList()
        val blobStore = blobStoreFor(conversationDir)
        return readMessageRows(transcript).flatMapIndexed { index, row ->
            row?.let { projectRow(it, seqId = index + 1, blobStore) }.orEmpty()
        }
    }

    /**
     * The message body of every non-blank transcript line, in file order (null for an unparseable
     * line, so callers keep 1-based line positions as seq ids). Read through
     * [BoundedTranscriptReader] so no single JSON string value is materialized beyond
     * [maxInlineValueChars] (letta-mobile-by4vm.74).
     */
    private fun readMessageRows(transcript: File): List<JsonObject?> =
        BoundedTranscriptReader.readLines(transcript, maxInlineValueChars).map { boundedLine ->
            runCatching { json.parseToJsonElement(boundedLine.text).jsonObject }.getOrNull()
                ?.let(LocalBackendLayout::messageBody)
        }

    /**
     * Tool results letta.js persisted for the default conversation, keyed by tool_call_id. The live
     * stream announces tool calls but never their returns (letta-mobile-bm6x2).
     */
    fun readToolResults(agentId: AgentId): Map<String, StoredToolResult> {
        val transcript = File(LocalBackendLayout.defaultConversationDir(baseDir, agentId), LocalBackendLayout.TRANSCRIPT_FILE)
        if (!transcript.isFile) return emptyMap()
        return readMessageRows(transcript).filterNotNull().mapNotNull(::toolResultOf).toMap()
    }

    private fun toolResultOf(row: JsonObject): Pair<String, StoredToolResult>? {
        if (row.string("role") != "toolResult") return null
        val callId = row.string("toolCallId") ?: return null
        // An oversized text value arrives as the bounded marker; render the size placeholder.
        val body = (row["content"] as? JsonArray)
            ?.filterIsInstance<JsonObject>()
            ?.filter { it.string("type") == "text" }
            ?.map(::collapseOversizedPart)
            ?.mapNotNull { it.string("text") }
            ?.joinToString("\n")
            .orEmpty()
        return callId to StoredToolResult(toolCallId = callId, body = body, isError = row.isError())
    }

    /** One transcript row's identity plus its joined text, shared by the per-role projections. */
    private data class RowHead(val id: String, val date: String?, val seqId: Int, val text: String?)

    private fun projectRow(row: JsonObject, seqId: Int, blobStore: ImageBlobStore): List<LettaMessage> {
        val id = row.string("id") ?: return emptyList()
        val role = row.string("role") ?: return emptyList()
        // Collapse any bounded-oversized value BEFORE resolving image_ref pointers, so downstream
        // typed parsing never sees the marker and the row is still visible.
        val contentParts = (row["content"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            .map(::collapseOversizedPart)
            .map { part -> resolveImageRef(part, blobStore) }
        val head = RowHead(
            id = id,
            date = (row["metadata"] as? JsonObject)?.string("created_at"),
            seqId = seqId,
            text = joinedText(contentParts),
        )
        return when (role) {
            "user" -> userMessages(head)
            "assistant" -> assistantMessages(head, contentParts)
            "toolResult" -> toolReturnMessages(head, row)
            else -> emptyList()
        }
    }

    private fun joinedText(parts: List<JsonObject>): String? = parts
        .filter { it.string("type") == "text" }
        .mapNotNull { it.string("text") }
        .joinToString("\n")
        .takeIf { it.isNotBlank() }

    /** Synthetic context rows (system-reminder user messages letta.js injects) were never user-visible. */
    private fun userMessages(head: RowHead): List<LettaMessage> {
        val text = head.text ?: return emptyList()
        if (text.trimStart().startsWith("<system-reminder>")) return emptyList()
        return listOf(UserMessage(id = head.id, contentRaw = JsonPrimitive(text), date = head.date, seqId = head.seqId))
    }

    /** pi-ai records tool invocations as toolCall parts on the assistant row (letta-mobile-bm6x2). */
    private fun assistantMessages(head: RowHead, contentParts: List<JsonObject>): List<LettaMessage> = buildList {
        head.text?.let { add(AssistantMessage(id = head.id, contentRaw = JsonPrimitive(it), date = head.date, seqId = head.seqId)) }
        contentParts.filter { it.string("type") == "toolCall" }.forEachIndexed { callIndex, part ->
            toolCallMessage(head, callIndex, part)?.let { add(it) }
        }
    }

    private fun toolCallMessage(head: RowHead, callIndex: Int, part: JsonObject): ToolCallMessage? {
        val callId = part.string("id") ?: return null
        val name = part.string("name") ?: return null
        return ToolCallMessage(
            id = "${head.id}-tool-$callIndex",
            toolCall = ToolCall(id = callId, name = name, arguments = part["arguments"]?.toString()),
            date = head.date,
            seqId = head.seqId,
        )
    }

    private fun toolReturnMessages(head: RowHead, row: JsonObject): List<LettaMessage> {
        val callId = row.string("toolCallId") ?: return emptyList()
        return listOf(
            ToolReturnMessage(
                id = head.id,
                toolCallId = callId,
                status = if (row.isError()) "error" else "success",
                toolReturnRaw = JsonPrimitive(head.text.orEmpty()),
                date = head.date,
                seqId = head.seqId,
            ),
        )
    }

    /**
     * Resolve an image_ref pointer back to a normal image part from the blob store. The on-disk
     * pointer is a TEXT part carrying an `image_ref` field (never a type:"image_ref" part — letta.js
     * replays messages.jsonl to the provider). Falls open to "[image unavailable]" text.
     */
    private fun resolveImageRef(part: JsonObject, blobStore: ImageBlobStore): JsonObject {
        val ref = (part["image_ref"] as? JsonPrimitive)?.content ?: return part
        val mediaType = (part["mediaType"] as? JsonPrimitive)?.content ?: "image/jpeg"
        val bytes = blobStore.getBytes(ref) ?: return textPart("[image unavailable]")
        // The flat image shape letta.js persists ({type:image, mimeType, data}).
        return buildJsonObject {
            put("type", JsonPrimitive("image"))
            put("mimeType", JsonPrimitive(mediaType))
            put("data", JsonPrimitive(Base64.getEncoder().encodeToString(bytes)))
        }
    }

    /**
     * Replaces a content part whose only value [BoundedTranscriptReader] collapsed (an oversized
     * `data`/`text` string) with a small `text` placeholder carrying the approximate size, instead
     * of the raw marker string (letta-mobile-lgns8.20). Every other part is returned unchanged.
     */
    private fun collapseOversizedPart(part: JsonObject): JsonObject {
        val type = part.string("type")
        val oversizedChars = oversizedCharsOf(type, part) ?: return part
        // Base64 -> bytes is roughly 3/4 of the character count; a human-readable label only.
        val approxMb = (oversizedChars * 3 / 4) / (1024.0 * 1024.0)
        val what = if (type == "image") "image" else "content"
        return buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive("[$what omitted: too large to load (~%.1f MB)]".format(approxMb)))
            put("oversized", JsonPrimitive(true))
        }
    }

    private fun oversizedCharsOf(type: String?, part: JsonObject): Long? = when (type) {
        "image" -> part.string("data")?.let(BoundedTranscriptReader::extractOversizedLength)
            ?: (part["source"] as? JsonObject)?.string("data")?.let(BoundedTranscriptReader::extractOversizedLength)
        "text" -> part.string("text")?.let(BoundedTranscriptReader::extractOversizedLength)
        else -> null
    }

    private fun textPart(text: String): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("text"))
        put("text", JsonPrimitive(text))
    }

    private fun JsonObject.isError(): Boolean = (this["isError"] as? JsonPrimitive)?.content == "true"

    private fun JsonObject.string(key: String): String? = this[key]?.stringOrNull()
}
