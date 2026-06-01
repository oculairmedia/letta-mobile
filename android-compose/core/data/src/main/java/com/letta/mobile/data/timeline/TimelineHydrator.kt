package com.letta.mobile.data.timeline

import com.letta.mobile.data.api.MessageApi
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Handles the hydration (initial history load) of a timeline from the Letta server.
 */
internal class TimelineHydrator(
    private val conversationId: String,
    private val messageApi: MessageApi,
    private val pendingLocalStore: PendingLocalStore,
    private val conversationCursorStore: ConversationCursorStore,
    private val writeMutex: Mutex,
    private val state: MutableStateFlow<Timeline>,
    private val events: MutableSharedFlow<TimelineSyncEvent>,
    private val holderHydrationSeed: MutableStateFlow<Timeline>,
) {
    suspend fun hydrate(
        limit: Int = 50,
        recordConversationCursor: Boolean = false,
        fallbackCursorSeq: Long? = null,
    ) {
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
            return
        }
        val timelineBeforeFetch = writeMutex.withLock { state.value }
        try {
            val rawFetchLimit = hydrateRawFetchLimit(limit)
            val response = normalizeHydratedMessageOrder(
                messageApi.listConversationMessages(
                    conversationId = ConversationId(conversationId),
                    limit = rawFetchLimit,
                    order = "desc",
                ).reversed()
            )
            val diskRecords = runCatching { pendingLocalStore.load(conversationId) }
                .getOrDefault(emptyList())
            val hydrateEndSeq = if (recordConversationCursor) {
                response.mapNotNull { it.seqId?.toLong() }
                    .plus(listOfNotNull(fallbackCursorSeq))
                    .maxOrNull()
            } else {
                null
            }

            val hydrated = writeMutex.withLock {
                TimelineHydrationReducer.reduce(
                    conversationId = conversationId,
                    serverMessagesChronological = response,
                    timelineBeforeFetch = timelineBeforeFetch,
                    currentTimeline = state.value,
                    diskRecords = diskRecords,
                ).also { result ->
                    state.value = result.timeline
                    holderHydrationSeed.value = result.timeline
                }
            }
            if (recordConversationCursor && hydrateEndSeq != null) {
                conversationCursorStore.recordFrame(conversationId, hydrateEndSeq)
                Telemetry.event(
                    "TimelineSync", "hydrate.cursorRepaired",
                    "conversationId" to conversationId,
                    "cursorSeq" to hydrateEndSeq,
                )
            }
            events.emit(TimelineSyncEvent.Hydrated(hydrated.visibleEventCount))
            timer.stop(
                "conversationId" to conversationId,
                "rawCount" to response.size,
                "eventCount" to hydrated.visibleEventCount,
                "cursorSeq" to (hydrateEndSeq ?: -1L),
            )
            dumpTimelineState("hydrate", conversationId, state.value)
        } catch (t: Throwable) {
            timer.stopError(t, "conversationId" to conversationId)
            throw t
        }
    }

    private companion object {
        const val DEFAULT_SHIM_CONVERSATION_PREFIX = "conv-default-"
    }
}
