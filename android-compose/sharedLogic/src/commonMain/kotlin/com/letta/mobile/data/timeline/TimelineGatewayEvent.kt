package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import kotlinx.coroutines.CompletableDeferred

/**
 * Serialized events dispatched through the Timeline Sync Linearizing Event Gateway.
 */
sealed interface TimelineGatewayEvent {
    data class StreamMessage(val message: LettaMessage, val ack: CompletableDeferred<Unit>? = null) : TimelineGatewayEvent
    data class LocalSendAppend(val pending: PendingSend, val sentAt: TimelineInstant, val ack: CompletableDeferred<Unit>) : TimelineGatewayEvent
    data class ReconcileAfterSendSnapshot(val otid: String, val serverMessages: List<LettaMessage>, val ack: CompletableDeferred<ReconcileAfterSendResult>) : TimelineGatewayEvent
    data class RecentMessagesSnapshot(val serverMessages: List<LettaMessage>, val telemetryName: String, val telemetryAttrs: List<Pair<String, Any?>>, val ack: CompletableDeferred<Int>) : TimelineGatewayEvent
    data class ExternalTransportLocalAppend(val content: String, val otid: String, val attachments: List<MessageContentPart.Image>, val sentAt: TimelineInstant, val ack: CompletableDeferred<String>) : TimelineGatewayEvent
    data class PostHandlerCollapse(val ack: CompletableDeferred<Unit>) : TimelineGatewayEvent
    data class RetrySend(val otid: String, val ack: CompletableDeferred<Unit>) : TimelineGatewayEvent
    data class MarkSent(val otid: String, val ack: CompletableDeferred<Unit>) : TimelineGatewayEvent
    data class MarkFailed(val otid: String, val ack: CompletableDeferred<Unit>) : TimelineGatewayEvent
}

data class PendingSend(
    val otid: String,
    val content: String,
    val attachments: List<MessageContentPart.Image> = emptyList(),
)
