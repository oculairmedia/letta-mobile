package com.letta.mobile.data.chat.projection

import com.letta.mobile.data.canvas.compose.ComposeKind
import com.letta.mobile.data.chat.projection.meridian.MeridianCommandCall
import com.letta.mobile.data.canvas.compose.ComposeReceipt
import com.letta.mobile.data.canvas.compose.ComposeReceiptItem
import com.letta.mobile.data.canvas.compose.ComposeStatus
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.timeline.TimelineEvent
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * One `canvas_compose` call read into its [CanvasArtifactReceipt] (letta-mobile-bglj6.13): the
 * receipt its return says, or the pending, refused or degraded form, named from the request where
 * the return does not say.
 */
internal class ComposeCallReading private constructor(
    private val callId: String?,
    private val request: RequestSummary,
    private val fallbackId: String,
    private val result: String?,
    private val isError: Boolean,
    private val truncated: Boolean,
) {
    fun receipt(): CanvasArtifactReceipt {
        val text = result ?: return fromRequest(CanvasArtifactStatus.Pending)
        return Settled(text).receipt()
    }

    /** The receipt from the request alone: no revision or bounds, the request's title and kinds. */
    private fun fromRequest(status: CanvasArtifactStatus, error: CanvasArtifactError? = null) = CanvasArtifactReceipt(
        artifactId = fallbackId,
        canvasId = request.canvasId,
        revision = null,
        status = status,
        title = request.title,
        kinds = request.kinds,
        itemCount = request.itemCount,
        bounds = null,
        error = error,
        toolCallId = callId,
    )

    /** The call has returned [text]. */
    private inner class Settled(private val text: String) {
        fun receipt(): CanvasArtifactReceipt {
            val body = if (truncated) null else parseObject(text)?.unwrapped()
            return when {
                body == null -> unreadable()
                isError || body.isRefusal() -> refused(body)
                else -> published(body) ?: degraded()
            }
        }

        /** A return that is not a JSON object (or was cut): an error's text, else the degraded form. */
        private fun unreadable(): CanvasArtifactReceipt {
            if (!isError || truncated) return degraded()
            val message = text.trim().take(MAX_ERROR_CHARS).ifBlank { null }
            return fromRequest(CanvasArtifactStatus.Failed, CanvasArtifactError(code = null, message = message))
        }

        private fun published(body: JsonObject): CanvasArtifactReceipt? {
            val receipt = decodeReceipt(body) ?: return null
            val kinds = LinkedHashSet<ComposeKind>()
            val pieceIds = mutableListOf<String>()
            fun visit(item: ComposeReceiptItem) {
                kinds += item.kind
                pieceIds += item.boardId(receipt.artifactId)
                item.children.orEmpty().forEach(::visit)
            }
            receipt.items.forEach(::visit)
            return CanvasArtifactReceipt(
                artifactId = receipt.artifactId,
                canvasId = receipt.canvasId,
                revision = receipt.revision,
                status = if (receipt.status == ComposeStatus.DRY_RUN) CanvasArtifactStatus.DryRun else CanvasArtifactStatus.Published,
                title = receipt.title ?: request.title,
                kinds = kinds.toList(),
                itemCount = pieceIds.size,
                bounds = receipt.bounds,
                toolCallId = callId,
                pieceIds = pieceIds,
            )
        }

        private fun refused(body: JsonObject): CanvasArtifactReceipt {
            val problems = body["problems"] as? JsonArray
            val first = problems?.firstOrNull() as? JsonObject
            val message = first?.string("message") ?: body.string("message") ?: body.string("error")
            val error = CanvasArtifactError(
                code = body.string("code"),
                message = message?.take(MAX_ERROR_CHARS),
                problemCount = problems?.size ?: 0,
            )
            return fromRequest(CanvasArtifactStatus.Failed, error).copy(
                artifactId = body.string("artifact_id") ?: fallbackId,
                canvasId = body.string("canvas_id") ?: request.canvasId,
            )
        }

        /**
         * A return that is truncated or unreadable still yields a part (plan 3.5): published or failed
         * by the return's error flag, named from the request, with no bounds. The ids are read from
         * the text when a preview kept them.
         */
        private fun degraded(): CanvasArtifactReceipt {
            val status = if (isError) CanvasArtifactStatus.Failed else CanvasArtifactStatus.Published
            val error = if (isError) CanvasArtifactError(code = null, message = null) else null
            return fromRequest(status, error).copy(
                artifactId = request.artifactId ?: ARTIFACT_ID_IN_TEXT.find(text)?.groupValues?.get(1) ?: fallbackId,
                canvasId = request.canvasId ?: CANVAS_ID_IN_TEXT.find(text)?.groupValues?.get(1),
            )
        }
    }

    companion object {
        /**
         * [fromCli]: the call ran through the `meridian` CLI (letta-mobile-jna0o.6), so its return
         * is the shell's output and the receipt JSON is read off its stdout.
         */
        fun of(event: TimelineEvent, call: ToolCall, ordinal: Int, fromCli: Boolean = false): ComposeCallReading {
            val callId = call.effectiveId.takeIf { it.isNotBlank() }
            val request = RequestSummary.of(call.arguments)
            val returned = event.returnText(callId, ordinal)
            val result = if (fromCli) MeridianCommandCall.stdoutJson(returned) else returned
            return ComposeCallReading(
                callId = callId,
                request = request,
                fallbackId = request.artifactId ?: callId?.let { "call:$it" } ?: "call:${CanvasArtifactReceipts.eventKey(event)}#$ordinal",
                result = result,
                isError = event.returnIsError(callId) || (fromCli && MeridianCommandCall.isErrorResult(result)),
                truncated = event.returnTruncated(callId),
            )
        }

        private const val MAX_ERROR_CHARS = 280
        private val ARTIFACT_ID_IN_TEXT = Regex("\"artifact_id\"\\s*:\\s*\"([^\"]{1,64})\"")
        private val CANVAS_ID_IN_TEXT = Regex("\"canvas_id\"\\s*:\\s*\"([^\"]{1,256})\"")
    }
}

/** What the request names, read leniently: the card's text while pending or when the return is lost. */
internal data class RequestSummary(
    val artifactId: String?,
    val canvasId: String?,
    val title: String?,
    val kinds: List<ComposeKind>,
    val itemCount: Int,
) {
    companion object {
        fun of(arguments: String?): RequestSummary {
            val args = arguments?.let(::parseObject)
            val kinds = LinkedHashSet<ComposeKind>()
            var count = 0
            fun visit(items: JsonElement?) {
                (items as? JsonArray)?.forEach { element ->
                    val item = element as? JsonObject ?: return@forEach
                    count++
                    item.composeKind()?.let(kinds::add)
                    visit(item["children"])
                }
            }
            visit(args?.get("items"))
            return RequestSummary(
                artifactId = args?.string("artifact_id"),
                canvasId = args?.string("canvas_id"),
                title = args?.string("title")?.takeIf { it.isNotBlank() },
                kinds = kinds.toList(),
                itemCount = count,
            )
        }

        private fun JsonObject.composeKind(): ComposeKind? {
            val name = string("kind") ?: return null
            return ComposeKind.entries.firstOrNull { it.name == name }
        }
    }
}

private fun decodeReceipt(body: JsonObject): ComposeReceipt? = try {
    lenient.decodeFromJsonElement(ComposeReceipt.serializer(), body)
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

private fun JsonObject.isRefusal(): Boolean = (this["ok"] as? JsonPrimitive)?.booleanOrNull == false

/** A return a host wrapped once (`{"message": "<receipt json>"}`) reads as the receipt inside. */
private fun JsonObject.unwrapped(): JsonObject {
    if (isReceiptShaped()) return this
    return WRAPPER_KEYS.firstNotNullOfOrNull { key -> string(key)?.let(::parseObject)?.takeIf { it.isReceiptShaped() } } ?: this
}

private fun JsonObject.isReceiptShaped(): Boolean = "ok" in this || "artifact_id" in this

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun parseObject(text: String): JsonObject? = try {
    lenient.parseToJsonElement(text.trim()) as? JsonObject
} catch (e: SerializationException) {
    null
} catch (e: IllegalArgumentException) {
    null
}

private val lenient = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

private val WRAPPER_KEYS = listOf("message", "result", "content", "return_value")
