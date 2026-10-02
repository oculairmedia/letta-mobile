package com.letta.mobile.data.chat.projection

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.canvas.CanvasToolContract
import com.letta.mobile.data.canvas.compose.ComposeBounds
import com.letta.mobile.data.canvas.compose.ComposeKind
import com.letta.mobile.data.model.ToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.TimelineEvent

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
 * letta-mobile-bglj6.13 (canvas_compose C8): the chat-side part of one compose call, shown as a
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
 * For every `canvas_compose` call (in a Local or Confirmed TOOL_CALL event) the receipt is read
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
 *
 * Reading one call's return is [ComposeCallReading]; finding the narrating event is [ReceiptNarration].
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
        return ReceiptCollector(events).collect()
    }

    /** [message] with the receipts [attach] filed under [event]; the same instance when there are none. */
    fun Map<String, List<CanvasArtifactReceipt>>.applyTo(event: TimelineEvent, message: UiMessage): UiMessage {
        if (isEmpty()) return message
        val receipts = this[eventKey(event)] ?: return message
        return if (message.artifacts == receipts) message else message.copy(artifacts = receipts)
    }

    /** The receipt one call's return says, or its pending / degraded form. Exposed for tests. */
    fun receiptFor(event: TimelineEvent, call: ToolCall, ordinal: Int = 0): CanvasArtifactReceipt =
        ComposeCallReading.of(event, call, ordinal).receipt()

    private val PROVIDER_SAFE_COMPOSE = CanvasToolContract.COMPOSE.replace('.', '_')
}

/** One pass of [CanvasArtifactReceipts.attach] over [events] that hold a compose call. */
private class ReceiptCollector(private val events: List<TimelineEvent>) {
    private val byCall = LinkedHashMap<String, Found>()

    fun collect(): Map<String, List<CanvasArtifactReceipt>> {
        events.indices.filter { events[it].hasComposeCall() }.forEach(::addCalls)
        return latestPerArtifact().groupBy({ CanvasArtifactReceipts.eventKey(events[it.target]) }, { it.receipt })
    }

    private fun addCalls(index: Int) {
        val event = events[index]
        val target = ReceiptNarration(events, index).target()
        event.toolCallList().forEachIndexed { ordinal, call ->
            if (CanvasArtifactReceipts.isComposeTool(call.name)) {
                val receipt = CanvasArtifactReceipts.receiptFor(event, call, ordinal)
                val callKey = call.effectiveId.ifBlank { "${CanvasArtifactReceipts.eventKey(event)}#$ordinal" }
                keepMostAdvanced(callKey, Found(order = index * MAX_CALLS_PER_EVENT + ordinal, target = target, receipt = receipt))
            }
        }
    }

    /** A call seen twice keeps its most advanced receipt, the later one on a tie. */
    private fun keepMostAdvanced(callKey: String, found: Found) {
        val existing = byCall[callKey]
        if (existing == null || found.receipt.status.rank() >= existing.receipt.status.rank()) byCall[callKey] = found
    }

    /** One receipt per artifact, the latest call's, in call order. */
    private fun latestPerArtifact(): List<Found> {
        val byArtifact = LinkedHashMap<String, Found>()
        byCall.values.sortedBy { it.order }.forEach { found ->
            byArtifact.remove(found.receipt.artifactId)
            byArtifact[found.receipt.artifactId] = found
        }
        return byArtifact.values.sortedBy { it.order }
    }

    private fun CanvasArtifactStatus.rank(): Int = if (this == CanvasArtifactStatus.Pending) 0 else 1

    private data class Found(val order: Int, val target: Int, val receipt: CanvasArtifactReceipt)

    private companion object {
        const val MAX_CALLS_PER_EVENT = 1024
    }
}
