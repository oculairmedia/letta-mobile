package com.letta.mobile.data.chat.projection

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.compose.ComposeBounds
import com.letta.mobile.data.canvas.compose.ComposeKind
import com.letta.mobile.data.canvas.compose.ComposeReceipt
import com.letta.mobile.data.canvas.compose.ComposeReceiptItem
import com.letta.mobile.data.canvas.compose.ComposeStatus
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Where a compose call stands, as the chat card shows it. */
enum class CanvasArtifactStatus {
    /** The call is in flight: no return yet. */
    Pending,

    /** On the board. */
    Published,

    /** Refused: nothing was published. */
    Failed,

    /** A preview the agent asked for; nothing was published. */
    DryRun,
}

/** Why a compose was refused: the refusal's code and its first problem, with how many there were. */
@Immutable
data class CanvasArtifactError(
    val code: String?,
    val message: String?,
    val problemCount: Int = 0,
)

/**
 * letta-mobile-bglj6.13 (canvas.compose C8): the chat-side part of one compose call, shown as a
 * card on the message that narrates it. Derived, never persisted: it is read off the compose tool
 * return already stored on the TOOL_CALL timeline event (docs/design/canvas-compose-plan.md, D4).
 *
 * [kinds] are the item kinds in the order they first appear (group children included) and
 * [itemCount] counts every item, the children of groups too, as the plan's fixture 5.3 does.
 */
@Immutable
data class CanvasArtifactReceipt(
    val artifactId: String,
    val canvasId: String?,
    val revision: Long?,
    val status: CanvasArtifactStatus,
    /** The artifact's title, or null when neither the return nor the request named one. */
    val title: String?,
    val kinds: List<ComposeKind>,
    val itemCount: Int,
    /** World-unit rectangle of the artifact on the board; null while pending or when unreadable. */
    val bounds: ComposeBounds?,
    val error: CanvasArtifactError? = null,
    val toolCallId: String? = null,
    /**
     * The board ids of the artifact's pieces, group children included, in receipt order: what the
     * receipt names, else derived as `cmp-<artifactId>-<key>` (receipts no longer carry them,
     * letta-mobile-bglj6.14). Empty while pending, refused or unreadable.
     */
    val pieceIds: List<String> = emptyList(),
) {
    /** Only a published artifact is on a board to be shown. */
    val canShowOnCanvas: Boolean get() = status == CanvasArtifactStatus.Published
}

/**
 * The single-receipt rule (plan 3.5, D4), as a pure projection over a run of timeline events.
 *
 * For every `canvas.compose` call (in a Local or Confirmed TOOL_CALL event) the receipt is read
 * from its return (`toolReturnContentByCallId[callId]`, else `toolReturnContent`) and attached to
 * exactly one event:
 *
 * 1. the first ASSISTANT text event after the call in the same run, preferring one in the same
 *    step;
 * 2. else the last ASSISTANT text event before the call in the same step;
 * 3. else the TOOL_CALL event itself, so a receipt is never lost.
 *
 * "The same run" is the call's `runId`; an event without one (a Local, or older history) is scoped
 * to its turn, the events between two USER messages. The same call seen twice (a Local and its
 * Confirmed echo, or a replayed or observed delivery of one event) yields one receipt, the most
 * advanced one; the same `artifactId` from two calls (a retry) yields one receipt, the latest. How
 * an event arrived (live stream, replay, observer, relay fan-out) plays no part: the receipt is a
 * function of the events alone, so hydration on any device attaches the same parts.
 */
object CanvasArtifactReceipts {
    /** The key [attach] files receipts under; mirrors the chat projector's per-event key. */
    fun eventKey(event: TimelineEvent): String = when (event) {
        is TimelineEvent.Confirmed -> "c:${event.serverId}:${event.messageType.name}"
        is TimelineEvent.Local -> "l:${event.otid}"
    }

    /** The compose tool, under the contract's name or its provider-safe spelling (dots as underscores). */
    fun isComposeTool(name: String?): Boolean =
        name != null && (name == CanvasToolContract.COMPOSE || name == PROVIDER_SAFE_COMPOSE)

    /**
     * Receipts by the [eventKey] of the event that carries them, in call order within an event.
     * Pure, and cheap when no compose call is present (one pass, no allocation).
     */
    fun attach(events: List<TimelineEvent>): Map<String, List<CanvasArtifactReceipt>> {
        if (events.none { it.hasComposeCall() }) return emptyMap()
        val byCall = LinkedHashMap<String, Found>()
        events.forEachIndexed { index, event ->
            if (!event.hasComposeCall()) return@forEachIndexed
            val target = narrationIndex(events, index)
            event.toolCallList().forEachIndexed callLoop@{ ordinal, call ->
                if (!isComposeTool(call.name)) return@callLoop
                val receipt = receiptFor(event, call, ordinal)
                val callKey = call.effectiveId.ifBlank { "${eventKey(event)}#$ordinal" }
                val found = Found(order = index * MAX_CALLS_PER_EVENT + ordinal, target = target, receipt = receipt)
                val existing = byCall[callKey]
                if (existing == null || found.receipt.status.rank() >= existing.receipt.status.rank()) byCall[callKey] = found
            }
        }
        val byArtifact = LinkedHashMap<String, Found>()
        byCall.values.sortedBy { it.order }.forEach { found ->
            byArtifact.remove(found.receipt.artifactId)
            byArtifact[found.receipt.artifactId] = found
        }
        return byArtifact.values
            .sortedBy { it.order }
            .groupBy({ eventKey(events[it.target]) }, { it.receipt })
    }

    /** [message] with the receipts [attach] filed under [event]; the same instance when there are none. */
    fun Map<String, List<CanvasArtifactReceipt>>.applyTo(event: TimelineEvent, message: UiMessage): UiMessage {
        if (isEmpty()) return message
        val receipts = this[eventKey(event)] ?: return message
        return if (message.artifacts == receipts) message else message.copy(artifacts = receipts)
    }

    /** The receipt one call's return says, or its pending / degraded form. Exposed for tests. */
    fun receiptFor(event: TimelineEvent, call: ToolCall, ordinal: Int = 0): CanvasArtifactReceipt {
        val callId = call.effectiveId.takeIf { it.isNotBlank() }
        val result = callId?.let { event.returnByCallId()[it] } ?: if (ordinal == 0) event.returnContent() else null
        val isError = callId?.let { event.returnIsErrorByCallId()[it] } ?: event.returnIsError()
        val truncated = callId != null && event is TimelineEvent.Confirmed && callId in event.toolReturnTruncationByCallId
        val request = RequestSummary.of(call.arguments)
        val fallbackId = request.artifactId ?: callId?.let { "call:$it" } ?: "call:${eventKey(event)}#$ordinal"
        if (result == null) {
            return CanvasArtifactReceipt(
                artifactId = fallbackId,
                canvasId = request.canvasId,
                revision = null,
                status = CanvasArtifactStatus.Pending,
                title = request.title,
                kinds = request.kinds,
                itemCount = request.itemCount,
                bounds = null,
                toolCallId = callId,
            )
        }
        val body = if (truncated) null else parseObject(result)?.unwrapped()
        return when {
            body != null && (isError || body.isRefusal()) -> refused(body, request, fallbackId, callId)
            body != null -> published(body, request, callId) ?: degraded(result, isError, request, fallbackId, callId)
            isError && !truncated -> CanvasArtifactReceipt(
                artifactId = fallbackId,
                canvasId = request.canvasId,
                revision = null,
                status = CanvasArtifactStatus.Failed,
                title = request.title,
                kinds = request.kinds,
                itemCount = request.itemCount,
                bounds = null,
                error = CanvasArtifactError(code = null, message = result.trim().take(MAX_ERROR_CHARS).ifBlank { null }),
                toolCallId = callId,
            )
            else -> degraded(result, isError, request, fallbackId, callId)
        }
    }

    private fun published(body: JsonObject, request: RequestSummary, callId: String?): CanvasArtifactReceipt? {
        val receipt = try {
            lenient.decodeFromJsonElement(ComposeReceipt.serializer(), body)
        } catch (e: SerializationException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
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

    private fun refused(body: JsonObject, request: RequestSummary, fallbackId: String, callId: String?): CanvasArtifactReceipt {
        val problems = body["problems"] as? JsonArray
        val first = problems?.firstOrNull() as? JsonObject
        val message = first?.string("message") ?: body.string("message") ?: body.string("error")
        return CanvasArtifactReceipt(
            artifactId = body.string("artifact_id") ?: fallbackId,
            canvasId = body.string("canvas_id") ?: request.canvasId,
            revision = null,
            status = CanvasArtifactStatus.Failed,
            title = request.title,
            kinds = request.kinds,
            itemCount = request.itemCount,
            bounds = null,
            error = CanvasArtifactError(
                code = body.string("code"),
                message = message?.take(MAX_ERROR_CHARS),
                problemCount = problems?.size ?: 0,
            ),
            toolCallId = callId,
        )
    }

    /**
     * A return that is truncated or unreadable still yields a part (plan 3.5): published or failed
     * by the return's error flag, named from the request, with no bounds. The ids are read from
     * the text when a preview kept them.
     */
    private fun degraded(
        result: String,
        isError: Boolean,
        request: RequestSummary,
        fallbackId: String,
        callId: String?,
    ): CanvasArtifactReceipt = CanvasArtifactReceipt(
        artifactId = request.artifactId ?: ARTIFACT_ID_IN_TEXT.find(result)?.groupValues?.get(1) ?: fallbackId,
        canvasId = request.canvasId ?: CANVAS_ID_IN_TEXT.find(result)?.groupValues?.get(1),
        revision = null,
        status = if (isError) CanvasArtifactStatus.Failed else CanvasArtifactStatus.Published,
        title = request.title,
        kinds = request.kinds,
        itemCount = request.itemCount,
        bounds = null,
        error = if (isError) CanvasArtifactError(code = null, message = null) else null,
        toolCallId = callId,
    )

    /**
     * The narrating message for the call at [index] (see the class comment), or [index] itself.
     * Scans only the call's own run (or turn), so the cost is bounded by one turn.
     */
    private fun narrationIndex(events: List<TimelineEvent>, index: Int): Int {
        val call = events[index]
        val runId = call.runIdOrNull()
        val stepId = call.stepIdOrNull()
        fun inScope(event: TimelineEvent) = runId == null || event.runIdOrNull() == runId
        var firstAfter = -1
        var j = index + 1
        while (j < events.size) {
            val event = events[j]
            if (event.isUserTurn()) break
            if (inScope(event) && event.isNarration()) {
                if (stepId != null && event.stepIdOrNull() == stepId) return j
                if (firstAfter < 0) firstAfter = j
            }
            j++
        }
        if (firstAfter >= 0) return firstAfter
        if (stepId != null) {
            var k = index - 1
            while (k >= 0) {
                val event = events[k]
                if (event.isUserTurn()) break
                if (inScope(event) && event.stepIdOrNull() == stepId && event.isNarration()) return k
                k--
            }
        }
        return index
    }

    private data class Found(val order: Int, val target: Int, val receipt: CanvasArtifactReceipt)

    /** What the request names, read leniently: the card's text while pending or when the return is lost. */
    private data class RequestSummary(
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
                        item.string("kind")?.let { name -> ComposeKind.entries.firstOrNull { it.name == name } }?.let(kinds::add)
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
        }
    }

    private fun CanvasArtifactStatus.rank(): Int = if (this == CanvasArtifactStatus.Pending) 0 else 1

    private fun TimelineEvent.hasComposeCall(): Boolean {
        val type = when (this) {
            is TimelineEvent.Confirmed -> messageType
            is TimelineEvent.Local -> messageType
        }
        return type == TimelineMessageType.TOOL_CALL && toolCallList().any { isComposeTool(it.name) }
    }

    private fun TimelineEvent.toolCallList(): List<ToolCall> = when (this) {
        is TimelineEvent.Confirmed -> toolCalls
        is TimelineEvent.Local -> toolCalls
    }

    private fun TimelineEvent.returnByCallId(): Map<String, String> = when (this) {
        is TimelineEvent.Confirmed -> toolReturnContentByCallId
        is TimelineEvent.Local -> toolReturnContentByCallId
    }

    private fun TimelineEvent.returnIsErrorByCallId(): Map<String, Boolean> = when (this) {
        is TimelineEvent.Confirmed -> toolReturnIsErrorByCallId
        is TimelineEvent.Local -> toolReturnIsErrorByCallId
    }

    private fun TimelineEvent.returnContent(): String? = when (this) {
        is TimelineEvent.Confirmed -> toolReturnContent
        is TimelineEvent.Local -> toolReturnContent
    }

    private fun TimelineEvent.returnIsError(): Boolean = when (this) {
        is TimelineEvent.Confirmed -> toolReturnIsError
        is TimelineEvent.Local -> toolReturnIsError
    }

    private fun TimelineEvent.runIdOrNull(): String? = (this as? TimelineEvent.Confirmed)?.runId?.takeIf { it.isNotBlank() }

    private fun TimelineEvent.stepIdOrNull(): String? = (this as? TimelineEvent.Confirmed)?.stepId?.takeIf { it.isNotBlank() }

    private fun TimelineEvent.isUserTurn(): Boolean = when (this) {
        is TimelineEvent.Confirmed -> messageType == TimelineMessageType.USER
        is TimelineEvent.Local -> messageType == TimelineMessageType.USER
    }

    private fun TimelineEvent.isNarration(): Boolean {
        val type = when (this) {
            is TimelineEvent.Confirmed -> messageType
            is TimelineEvent.Local -> messageType
        }
        return type == TimelineMessageType.ASSISTANT && content.isNotBlank()
    }

    private fun JsonObject.isRefusal(): Boolean = (this["ok"] as? JsonPrimitive)?.booleanOrNull == false

    /** A return a host wrapped once (`{"message": "<receipt json>"}`) reads as the receipt inside. */
    private fun JsonObject.unwrapped(): JsonObject {
        if ("ok" in this || "artifact_id" in this) return this
        for (key in WRAPPER_KEYS) {
            val inner = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::parseObject) ?: continue
            if ("ok" in inner || "artifact_id" in inner) return inner
        }
        return this
    }

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

    private val PROVIDER_SAFE_COMPOSE = CanvasToolContract.COMPOSE.replace('.', '_')
    private val WRAPPER_KEYS = listOf("message", "result", "content", "return_value")
    private val ARTIFACT_ID_IN_TEXT = Regex("\"artifact_id\"\\s*:\\s*\"([^\"]{1,64})\"")
    private val CANVAS_ID_IN_TEXT = Regex("\"canvas_id\"\\s*:\\s*\"([^\"]{1,256})\"")
    private const val MAX_ERROR_CHARS = 280
    private const val MAX_CALLS_PER_EVENT = 1024
}
