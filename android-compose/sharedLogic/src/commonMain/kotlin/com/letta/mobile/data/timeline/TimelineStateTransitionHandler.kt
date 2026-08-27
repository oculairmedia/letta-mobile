package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.PersistentList

/**
 * Handles timeline local event additions, retry transitions, and delivery state transitions (sent/failed).
 */
class TimelineStateTransitionHandler(
    private val conversationId: String,
    private val processor: TimelineProcessor,
) {
    suspend fun applyLocalSendAppend(event: TimelineGatewayEvent.LocalSendAppend) {
        val result = processor.submit(
            TimelineMutation.LocalAppend(event.pending, event.sentAt, TimelineLocalAppendMode.SEND),
        ).appliedResultOrThrow()
        Telemetry.event(
            "TimelineSync", "send.localAppended",
            "otid" to event.pending.otid,
            "conversationId" to conversationId,
            "contentLength" to event.pending.content.length,
            "changed" to result.changed,
        )
        event.ack.complete(Unit)
    }

    suspend fun applyRetrySend(event: TimelineGatewayEvent.RetrySend) {
        processor.submit(TimelineMutation.RetryLocal(event.otid)).appliedResultOrThrow()
        event.ack.complete(Unit)
    }

    suspend fun applyMarkSent(event: TimelineGatewayEvent.MarkSent) {
        processor.submit(TimelineMutation.MarkLocalSent(event.otid)).appliedResultOrThrow()
        event.ack.complete(Unit)
    }

    suspend fun applyMarkFailed(event: TimelineGatewayEvent.MarkFailed) {
        processor.submit(TimelineMutation.MarkLocalFailed(event.otid)).appliedResultOrThrow()
        event.ack.complete(Unit)
    }

    /**
     * letta-mobile-mxwtn: synchronous optimistic Local append used by the
     * platform send coordinator BEFORE the transport call so the user bubble
     * reaches the timeline state in the same frame as the composer clear —
     * no round-trip through [eventQueue] / [loopScope] / coroutine scheduling.
     *
     * Mirrors the body of [applyLocalSendAppend] minus the [sendQueue.send]
     * handoff (the caller is responsible for queueing the actual HTTP send
     * via the regular `send()` entry point, typically with the same otid) and
     * minus the [events] emission (callers that need [TimelineSyncEvent.LocalAppended]
     * can emit it themselves, or rely on the existing event-queue path's
     * emission for parity).
     *
     * Returns `true` when the Local event was appended, `false` when an event
     * with the same otid already exists (idempotent — the existing event is
     * kept so a duplicate send path does not fork the timeline).
     */
    suspend fun appendOptimisticLocalSync(
        otid: String,
        content: String,
        attachments: PersistentList<MessageContentPart.Image>,
        sentAt: TimelineInstant,
    ): Boolean {
        val appended = processor.submit(
            TimelineMutation.LocalAppend(
                pending = PendingSend(otid, content, attachments),
                sentAt = sentAt,
                mode = TimelineLocalAppendMode.OPTIMISTIC,
            ),
        ).appliedResultOrThrow().changed
        if (appended) {
            Telemetry.event(
                "TimelineSync", "send.optimisticLocalAppended",
                "otid" to otid,
                "conversationId" to conversationId,
                "contentLength" to content.length,
            )
        }
        return appended
    }

    /**
     * letta-mobile-mxwtn: synchronous FAILED transition. Mirrors
     * [applyMarkFailed] but takes the write mutex directly so the caller's
     * failure handler can flip the bubble's deliveryState in the same frame
     * the HTTP error is surfaced, without round-tripping through the event
     * queue. A no-op when no Local event with that otid exists.
     */
    suspend fun markOptimisticLocalFailedSync(otid: String) {
        processor.submit(TimelineMutation.MarkLocalFailed(otid)).appliedResultOrThrow()
        Telemetry.event(
            "TimelineSync", "send.optimisticLocalFailed",
            "otid" to otid,
            "conversationId" to conversationId,
        )
    }

    /**
     * letta-mobile-mxwtn: synchronous SENT transition (delivery acknowledged
     * by the transport but no Confirmed echo yet). Mirrors [applyMarkSent].
     */
    suspend fun markOptimisticLocalSentSync(otid: String) {
        processor.submit(TimelineMutation.MarkLocalSent(otid)).appliedResultOrThrow()
    }
}
