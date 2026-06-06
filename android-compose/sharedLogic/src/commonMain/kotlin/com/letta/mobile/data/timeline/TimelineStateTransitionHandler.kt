package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry
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
                state.value = state.value.copy(events = state.value.events.map {
                    if (it.otid == event.otid && it is TimelineEvent.Local) {
                        it.copy(deliveryState = DeliveryState.SENDING)
                    } else it
                })
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
}
