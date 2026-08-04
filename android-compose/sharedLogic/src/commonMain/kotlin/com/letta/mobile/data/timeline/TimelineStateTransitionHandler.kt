package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Handles timeline local event additions, retry transitions, and delivery state transitions (sent/failed).
 */
class TimelineStateTransitionHandler(
    private val conversationId: String,
    private val state: MutableStateFlow<Timeline>,
    private val events: MutableSharedFlow<TimelineSyncEvent>,
    private val sendQueue: Channel<PendingSend>,
    private val writeMutex: Mutex,
) {
    suspend fun applyLocalSendAppend(event: TimelineGatewayEvent.LocalSendAppend) {
        writeMutex.withLock {
            val local = TimelineEvent.Local(
                position = state.value.nextLocalPosition(),
                otid = event.pending.otid,
                content = event.pending.content,
                role = Role.USER,
                sentAt = event.sentAt,
                deliveryState = DeliveryState.SENDING,
                attachments = event.pending.attachments,
            )
            state.value = state.value.append(local)
            sendQueue.send(event.pending)
        }
        events.emit(TimelineSyncEvent.LocalAppended(event.pending.otid))
        Telemetry.event(
            "TimelineSync", "send.localAppended",
            "otid" to event.pending.otid,
            "conversationId" to conversationId,
            "contentLength" to event.pending.content.length,
        )
        event.ack.complete(Unit)
    }

    suspend fun applyRetrySend(event: TimelineGatewayEvent.RetrySend) {
        writeMutex.withLock {
            val existing = state.value.findByOtid(event.otid)
            if (existing is TimelineEvent.Local && existing.deliveryState == DeliveryState.FAILED) {
                // Recompute the fingerprint: the retried message may be a NON-tail
                // event, and data class copy() reuses stablePrefixVersion, so the
                // projector's fast path would otherwise suppress the FAILED→SENDING
                // repaint (Codex review).
                val persisted = state.value.events.map {
                    if (it.otid == event.otid && it is TimelineEvent.Local) {
                        it.copy(deliveryState = DeliveryState.SENDING)
                    } else it
                }.toTimelinePersistentList()
                state.value = state.value.copy(
                    events = persisted,
                    stablePrefixVersion = persisted.stablePrefixFingerprint(),
                )
                sendQueue.send(PendingSend(event.otid, existing.content, existing.attachments))
            }
        }
        event.ack.complete(Unit)
    }

    suspend fun applyMarkSent(event: TimelineGatewayEvent.MarkSent) {
        writeMutex.withLock {
            state.value = state.value.markSent(event.otid)
        }
        event.ack.complete(Unit)
    }

    suspend fun applyMarkFailed(event: TimelineGatewayEvent.MarkFailed) {
        writeMutex.withLock {
            state.value = state.value.markFailed(event.otid)
        }
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
        var appended = false
        writeMutex.withLock {
            if (state.value.findByOtid(otid) == null) {
                val local = TimelineEvent.Local(
                    position = state.value.nextLocalPosition(),
                    otid = otid,
                    content = content,
                    role = Role.USER,
                    sentAt = sentAt,
                    deliveryState = DeliveryState.SENDING,
                    attachments = attachments,
                )
                state.value = state.value.append(local)
                appended = true
            }
        }
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
        writeMutex.withLock {
            val existing = state.value.findByOtid(otid)
            if (existing is TimelineEvent.Local) {
                state.value = state.value.markFailed(otid)
            }
        }
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
        writeMutex.withLock {
            val existing = state.value.findByOtid(otid)
            if (existing is TimelineEvent.Local) {
                state.value = state.value.markSent(otid)
            }
        }
    }
}
