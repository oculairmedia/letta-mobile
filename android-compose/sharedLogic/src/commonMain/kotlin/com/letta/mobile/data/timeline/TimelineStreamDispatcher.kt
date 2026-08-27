package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage

/**
 * Handles SSE stream frame dispatch through the per-conversation processor.
 * The processor owns stream ordering and pending tool-return state.
 */
internal class TimelineStreamDispatcher(
    private val conversationId: String,
    private val agentId: String? = null,
    private val processor: TimelineProcessor,
    private val conversationCursorStore: ConversationCursorStore,
    private val onStreamFrameIngested: (() -> Unit)? = null,
) {
    suspend fun dispatch(message: LettaMessage, source: String = "unknown") {
        val acknowledgement = processor.submitWithBackpressure(
            TimelineMutation.StreamFrame(message = message, agentId = agentId),
        )
        when (acknowledgement) {
            is TimelineProcessorAck.Applied -> {
                message.seqId?.takeIf { it >= 0 }?.let { seq ->
                    conversationCursorStore.recordFrame(conversationId, seq.toLong())
                }
                onStreamFrameIngested?.invoke()
            }
            is TimelineProcessorAck.Rejected -> throw TimelineProcessorMutationException(
                "stream frame rejected from $source: ${acknowledgement.reason}",
            )
            is TimelineProcessorAck.Failed -> throw TimelineProcessorMutationException(
                "stream frame failed from $source: ${acknowledgement.reason}",
            )
        }
    }
}
