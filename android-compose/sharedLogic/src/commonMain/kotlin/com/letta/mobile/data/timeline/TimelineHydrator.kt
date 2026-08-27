package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.util.Telemetry
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Handles the hydration (initial history load) of a timeline from the Letta server.
 */
internal class TimelineHydrator(
    private val conversationId: String,
    private val messageApi: TimelineTransport,
    private val pendingLocalStore: PendingLocalStore,
    private val events: MutableSharedFlow<TimelineSyncEvent>,
    private val timelineProcessor: TimelineProcessor,
    private val onHydrationCommitted: (() -> Unit)? = null,
) {
    private val nextGeneration = atomic(timelineProcessor.state.value.hydrateGeneration)

    suspend fun hydrate(
        limit: Int = 50,
        recordConversationCursor: Boolean = false,
        fallbackCursorSeq: Long? = null,
    ): TimelineHydrationOutcome {
        val timer = Telemetry.startTimer("TimelineSync", "hydrate")
        if (conversationId.startsWith(DEFAULT_SHIM_CONVERSATION_PREFIX)) {
            Telemetry.event(
                "TimelineSync", "hydrate.skipped",
                "conversationId" to conversationId,
                "reason" to "defaultShimConversation",
                level = Telemetry.Level.WARN,
            )
            events.emit(TimelineSyncEvent.Hydrated(0))
            timer.stop(
                "conversationId" to conversationId,
                "rawCount" to 0,
                "eventCount" to 0,
                "cursorSeq" to -1L,
                "skipped" to true,
                "skipReason" to "defaultShimConversation",
            )
            return TimelineHydrationOutcome.DefaultShimAccepted
        }

        val generation = nextGeneration.incrementAndGet()
        val timelineBeforeFetch = timelineProcessor.state.value.timeline
        try {
            val response = fetchChronologicalMessages(limit)
            val cursorSequence = response.cursorSequence(recordConversationCursor, fallbackCursorSeq)
            val diskRecords = runCatching { pendingLocalStore.load(conversationId) }.getOrDefault(emptyList())
            when (val acknowledgement = timelineProcessor.submit(
                TimelineMutation.HydrateSnapshot(
                    generation = generation,
                    messages = response,
                    timelineBeforeFetch = timelineBeforeFetch,
                    diskRecords = diskRecords,
                    cursorSequence = cursorSequence,
                ),
            )) {
                is TimelineProcessorAck.Applied -> {
                    val hydrated = acknowledgement.result as? TimelineReductionResult.Hydrated
                        ?: error("hydrate acknowledgement did not carry hydration result")
                    notifyHydrationCommitted()
                    events.emit(TimelineSyncEvent.Hydrated(hydrated.visibleEventCount))
                    timer.stop(
                        "conversationId" to conversationId,
                        "rawCount" to response.size,
                        "eventCount" to hydrated.visibleEventCount,
                        "cursorSeq" to (cursorSequence ?: -1L),
                    )
                    dumpTimelineState("hydrate", conversationId, timelineProcessor.state.value.timeline)
                    return TimelineHydrationOutcome.Accepted
                }
                is TimelineProcessorAck.Rejected -> {
                    Telemetry.event(
                        "TimelineSync", "hydrate.rejected",
                        "conversationId" to conversationId,
                        "generation" to generation,
                        "reason" to acknowledgement.reason.toString(),
                        level = Telemetry.Level.WARN,
                    )
                    timer.stop("conversationId" to conversationId, "rejected" to true)
                    return TimelineHydrationOutcome.Rejected
                }
                is TimelineProcessorAck.Failed -> throw TimelineProcessorMutationException(
                    "timeline hydration mutation failed: ${acknowledgement.reason}",
                )
            }
        } catch (t: Throwable) {
            timer.stopError(t, "conversationId" to conversationId)
            throw t
        }
    }

    private suspend fun fetchChronologicalMessages(limit: Int) = normalizeHydratedMessageOrder(
        messageApi.listConversationMessages(
            conversationId = conversationId,
            limit = hydrateRawFetchLimit(limit),
            order = "desc",
        ).reversed()
    )

    private fun List<LettaMessage>.cursorSequence(
        shouldRecord: Boolean,
        fallbackCursorSeq: Long?,
    ): Long? =
        if (shouldRecord) mapNotNull { it.seqId?.toLong() }.plus(listOfNotNull(fallbackCursorSeq)).maxOrNull() else null

    private fun notifyHydrationCommitted() {
        onHydrationCommitted?.invoke()
    }

    private companion object {
        const val DEFAULT_SHIM_CONVERSATION_PREFIX = "conv-default-"
    }
}

internal enum class TimelineHydrationOutcome {
    Accepted,
    Rejected,
    DefaultShimAccepted,
}
