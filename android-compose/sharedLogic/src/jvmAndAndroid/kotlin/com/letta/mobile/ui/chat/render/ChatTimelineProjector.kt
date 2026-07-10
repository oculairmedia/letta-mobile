package com.letta.mobile.ui.chat.render

import com.letta.mobile.data.a2ui.A2uiHistoryExtractor
import com.letta.mobile.data.a2ui.A2uiMessage
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.util.Telemetry
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toPersistentList

/**
 * Platform-neutral projection of a [Timeline] snapshot into the ordered
 * [UiMessage] list (plus extracted A2UI history and the streaming-presentation
 * flags) that a chat surface renders.
 *
 * This is the streaming heart of the chat timeline: an incremental tail cache
 * recognises append-tail / replace-tail / no-change ticks so a high-frequency
 * server delta stream collapses to cheap tail updates instead of full
 * re-projections, and a [ChatMessageListChange] is emitted so the renderer can
 * skip whole-list rebuilds. It was extracted from Android's `ChatTimelineObserver`
 * so Android and desktop produce identical streaming output from the same
 * Timeline events. The class is stateful (it owns the projection cache and the
 * older-page prefix); each bound conversation should use its own instance or
 * call [reset] on rebind. It performs no IO, threading, or coalescing — callers
 * own the subscription loop and frame pacing.
 */
class ChatTimelineProjector {
    private var projectionCache: MutableMap<TimelineProjectionKey, CachedTimelineProjectionEvent> = mutableMapOf()
    private var lastProjectionSnapshot: CachedTimelineProjectionSnapshot? = null
    private var projectionTelemetryTick: Int = 0
    // letta-mobile-yflpp: running count of consecutive deduped no-op ticks,
    // used to throttle the uiProjection.suppressed telemetry sample.
    private var suppressedProjectionTick: Int = 0

    /**
     * Older messages fetched via pagination need to survive the next timeline
     * emission (which overwrites `messages` with live timeline contents). Hold a
     * per-conversation prefix and prepend it on every projection.
     */
    private var olderMessagesPrefix: Pair<String, List<UiMessage>> = "" to emptyList()

    /** Clears all per-conversation caches. Call on rebind/stop. */
    fun reset() {
        projectionCache = mutableMapOf()
        lastProjectionSnapshot = null
        projectionTelemetryTick = 0
        suppressedProjectionTick = 0
        olderMessagesPrefix = "" to emptyList()
    }

    fun olderPrefixFor(conversationId: String): List<UiMessage> {
        val (prefixConv, prefixList) = olderMessagesPrefix
        return if (prefixConv == conversationId) prefixList else emptyList()
    }

    fun mergeOlderPage(
        conversationId: String,
        olderMessages: List<UiMessage>,
        existingMessages: List<UiMessage>,
    ): List<UiMessage> {
        if (olderMessages.isEmpty()) return existingMessages

        val (prevConv, prevPrefix) = olderMessagesPrefix
        val basePrefix = if (prevConv == conversationId) prevPrefix else emptyList()
        val seenIds = HashSet<String>(basePrefix.size + olderMessages.size)
        val grown = ArrayList<UiMessage>(basePrefix.size + olderMessages.size)
        for (m in olderMessages) if (seenIds.add(m.id)) grown.add(m)
        for (m in basePrefix) if (seenIds.add(m.id)) grown.add(m)
        olderMessagesPrefix = conversationId to grown

        val existingIds = existingMessages.mapTo(mutableSetOf()) { it.id }
        return olderMessages.filterNot { it.id in existingIds } + existingMessages
    }

    /**
     * Project [timeline] into a [TimelineProjection]. [prefix] is the
     * pagination prefix (see [olderPrefixFor]); [previousState] is read only for
     * telemetry context (streaming/loading flags).
     */
    fun project(
        timeline: Timeline,
        prefix: List<UiMessage>,
        previousState: ChatUiState,
    ): TimelineProjection {
        val startedAtMs = System.currentTimeMillis()
        tailProjectionFastPath(timeline = timeline, prefix = prefix)?.let { fastProjection ->
            emitProjectionTelemetry(
                timeline = timeline,
                projection = fastProjection,
                prefix = prefix,
                previousState = previousState,
                startedAtMs = startedAtMs,
            )
            return fastProjection
        }

        val a2uiMessages = mutableListOf<A2uiMessage>()
        var eventsReused = 0
        var eventsProjected = 0
        val nextCache = HashMap<TimelineProjectionKey, CachedTimelineProjectionEvent>(timeline.events.size)
        val nextRecords = ArrayList<CachedTimelineProjectionEvent>(timeline.events.size)
        val live = ArrayList<UiMessage>(timeline.events.size)

        timeline.events.forEach { event ->
            val key = event.projectionKey()
            val cached = projectionCache[key]?.takeIf { it.event == event }
            val projected = if (cached != null) {
                eventsReused++
                cached
            } else {
                eventsProjected++
                event.projectForCacheRecord(key)
            }
            nextCache[key] = projected
            nextRecords += projected
            a2uiMessages += projected.a2uiMessages
            projected.uiMessage?.let(live::add)
        }
        projectionCache = nextCache

        val combined = combineOlderPrefix(prefix, live)
        val ui = combined.toImmutableList()
        val tailIsAssistant = timeline.events.lastOrNull().let {
            it is TimelineEvent.Confirmed && it.messageType == TimelineMessageType.ASSISTANT
        }
        var pendingCount = 0
        var confirmedCount = 0
        nextRecords.forEach {
            if (it.isLettaServerLocalPending) pendingCount++
            if (it.isConfirmedVisible) confirmedCount++
        }
        val anyLettaServerLocalPending = pendingCount > 0
        val anyConfirmed = confirmedCount > 0
        val liveToolCardCount = nextRecords.sumOf { it.toolCardCount }
        val prefixToolCardCount = prefix.sumOf { it.toolCardCount() }
        val previousSnapshot = lastProjectionSnapshot
        val renderedNoChange = previousSnapshot != null &&
            previousSnapshot.conversationId == timeline.conversationId &&
            previousSnapshot.uiSnapshot == ui &&
            previousSnapshot.a2uiMessages == a2uiMessages &&
            previousSnapshot.anyLettaServerLocalPending == anyLettaServerLocalPending &&
            previousSnapshot.anyConfirmed == anyConfirmed &&
            previousSnapshot.tailIsAssistant == tailIsAssistant &&
            previousSnapshot.prefixToolCardCount + previousSnapshot.toolCardCount ==
                prefixToolCardCount + liveToolCardCount
        val result = TimelineProjection(
            ui = if (renderedNoChange) previousSnapshot.uiSnapshot!! else ui,
            tailIsAssistant = tailIsAssistant,
            anyLettaServerLocalPending = anyLettaServerLocalPending,
            anyConfirmed = anyConfirmed,
            a2uiMessages = if (renderedNoChange) previousSnapshot.a2uiMessages else a2uiMessages,
            toolCardCount = prefixToolCardCount + liveToolCardCount,
            eventsReused = eventsReused,
            eventsProjected = eventsProjected,
            prefixEventsChecked = 0,
            fastPath = false,
            messageListChange = if (renderedNoChange) ChatMessageListChange.None else ChatMessageListChange.Full,
            noChange = renderedNoChange,
        )
        lastProjectionSnapshot = CachedTimelineProjectionSnapshot(
            conversationId = timeline.conversationId,
            stablePrefixVersion = timeline.stablePrefixVersion,
            records = nextRecords,
            liveMessages = live,
            prefix = prefix,
            a2uiMessages = a2uiMessages.toList(),
            anyLettaServerLocalPending = anyLettaServerLocalPending,
            anyConfirmed = anyConfirmed,
            toolCardCount = liveToolCardCount,
            prefixToolCardCount = prefixToolCardCount,
            uiSnapshot = ui,
            tailIsAssistant = tailIsAssistant,
            pendingCount = pendingCount,
            confirmedCount = confirmedCount,
        )
        emitProjectionTelemetry(
            timeline = timeline,
            projection = result,
            prefix = prefix,
            previousState = previousState,
            startedAtMs = startedAtMs,
        )
        return result
    }

    private fun tailProjectionFastPath(
        timeline: Timeline,
        prefix: List<UiMessage>,
    ): TimelineProjection? {
        val previous = lastProjectionSnapshot ?: return null
        if (previous.conversationId != timeline.conversationId || timeline.events.isEmpty()) return null
        if (previous.prefix !== prefix) return null
        // letta-mobile-ixtzn: guard against empty previous.records (e.g. after
        // reconnect-clear or frame-before-first-message ordering). The fast path
        // assumes ≥1 existing record to compare/extend the tail against; fall
        // through to full projection when the list is empty.
        if (previous.records.isEmpty()) return null

        val replaceTail = timeline.events.size == previous.records.size &&
            timeline.stablePrefixVersion == previous.stablePrefixVersion
        val appendTail = timeline.events.size == previous.records.size + 1 &&
            timeline.stablePrefixVersion == previous.stablePrefixVersion + 1
        if (!replaceTail && !appendTail) return null

        val tailEvent = timeline.events.last()
        val tailKey = tailEvent.projectionKey()
        val tailCached = projectionCache[tailKey]?.takeIf { it.event == tailEvent }
        val tailRecord = tailCached ?: tailEvent.projectForCacheRecord(tailKey)

        // letta-mobile-yflpp DEDUPE: during streaming the authoritative Timeline
        // StateFlow can re-emit ~20x/sec for the SAME visible content. The
        // reducer (TimelineStreamReducer.replaceByServerId + copy(liveCursor=…))
        // produces a NEW Timeline instance even when a delta merged to identical
        // text — e.g. STALE / EQUAL / SUFFIX_DUPLICATE merge branches, or a
        // frame that only advanced a non-rendered field like seqId/liveCursor.
        // That makes the Timeline `!=` (so StateFlow emits) while the *rendered*
        // tail is byte-identical to what's already on screen. Re-projecting it
        // would allocate a fresh ChatUiState and force a full Compose
        // recomposition over every tool card, pegging the UI thread so pointer
        // hit-testing / gesture handling can't get a clean pass — the
        // intermittent dead-tap-mid-stream bug.
        //
        // We compare the *projected* tail (uiMessage + a2ui + tool-card count +
        // visible flags), NOT the raw event, so a tick that only changed a
        // non-rendered field is correctly recognised as a no-op. On a match we
        // return a no-change projection and the collector skips the uiState
        // write entirely.
        if (replaceTail) {
            val previousTailRecord = previous.records.lastOrNull()
            if (previousTailRecord != null && tailRecord.rendersSameAs(previousTailRecord)) {
                return TimelineProjection(
                    ui = previous.uiSnapshot ?: combineOlderPrefix(prefix, previous.liveMessages).toImmutableList(),
                    tailIsAssistant = previous.tailIsAssistant,
                    anyLettaServerLocalPending = previous.anyLettaServerLocalPending,
                    anyConfirmed = previous.anyConfirmed,
                    a2uiMessages = previous.a2uiMessages,
                    toolCardCount = previous.prefixToolCardCount + previous.toolCardCount,
                    eventsReused = previous.records.size,
                    eventsProjected = 0,
                    prefixEventsChecked = 0,
                    fastPath = true,
                    messageListChange = ChatMessageListChange.None,
                    noChange = true,
                )
            }
        }

        val records = if (appendTail) {
            previous.records + tailRecord
        } else {
            previous.records.dropLast(1) + tailRecord
        }
        val live = if (appendTail) {
            if (tailRecord.uiMessage == null) previous.liveMessages else previous.liveMessages + tailRecord.uiMessage
        } else {
            // Safe to call .last() here: we've already guarded for isEmpty() above.
            val previousTailHadUi = previous.records.last().uiMessage != null
            when {
                previousTailHadUi && tailRecord.uiMessage != null -> previous.liveMessages.dropLast(1) + tailRecord.uiMessage
                previousTailHadUi -> previous.liveMessages.dropLast(1)
                tailRecord.uiMessage != null -> previous.liveMessages + tailRecord.uiMessage
                else -> previous.liveMessages
            }
        }
        val a2uiMessages = if (appendTail) {
            previous.a2uiMessages + tailRecord.a2uiMessages
        } else {
            // letta-mobile-yflpp: avoid the O(history) flatMap rebuild on every
            // streaming delta. The previous snapshot already holds the combined
            // a2ui list; strip the previous tail's contribution (a suffix of
            // known length) and append the new tail's, keeping this O(delta).
            // Safe to call .last() here: we've already guarded for isEmpty() above.
            val previousTailA2uiCount = previous.records.last().a2uiMessages.size
            val prefixA2ui = if (previousTailA2uiCount == 0) {
                previous.a2uiMessages
            } else {
                previous.a2uiMessages.subList(0, previous.a2uiMessages.size - previousTailA2uiCount)
            }
            prefixA2ui + tailRecord.a2uiMessages
        }
        // letta-mobile-flicker: avoid reallocating the full UI list on every
        // streaming tick. Reuse the prior immutable snapshot and append only
        // the new tail's UiMessage so Compose keeps prior keyed children stable
        // and only the new card recomposes. Falls back to a fresh
        // combineOlderPrefix walk if the prior snapshot is unavailable.
        val combined = if (appendTail && previous.uiSnapshot != null) {
            val appended = if (tailRecord.uiMessage == null) {
                previous.uiSnapshot
            } else {
                previous.uiSnapshot.toMutableList().also { it.add(tailRecord.uiMessage) }
            }
            appended.toPersistentList()
        } else if (appendTail) {
            combineOlderPrefix(prefix, live).toPersistentList()
        } else {
            null
        }
        // letta-mobile-yflpp: track pending/confirmed as counts on the snapshot
        // so a replaceTail can recompute the aggregate booleans in O(delta)
        // (subtract the old tail, add the new tail) instead of re-scanning the
        // whole history per streaming delta.
        // Safe to call .last() here: we've already guarded for isEmpty() above.
        val previousTailRecord = previous.records.last()
        val pendingCount = when {
            appendTail -> previous.pendingCount + (if (tailRecord.isLettaServerLocalPending) 1 else 0)
            else -> previous.pendingCount -
                (if (previousTailRecord.isLettaServerLocalPending) 1 else 0) +
                (if (tailRecord.isLettaServerLocalPending) 1 else 0)
        }
        val confirmedCount = when {
            appendTail -> previous.confirmedCount + (if (tailRecord.isConfirmedVisible) 1 else 0)
            else -> previous.confirmedCount -
                (if (previousTailRecord.isConfirmedVisible) 1 else 0) +
                (if (tailRecord.isConfirmedVisible) 1 else 0)
        }
        val anyLettaServerLocalPending = pendingCount > 0
        val anyConfirmed = confirmedCount > 0
        val toolCardCount = if (appendTail) {
            previous.toolCardCount + tailRecord.toolCardCount
        } else {
            // Safe to call .last() here: we've already guarded for isEmpty() above.
            previous.toolCardCount - previous.records.last().toolCardCount + tailRecord.toolCardCount
        }
        val tailIsAssistant = tailEvent is TimelineEvent.Confirmed && tailEvent.messageType == TimelineMessageType.ASSISTANT
        val ui: ImmutableList<UiMessage> = combined ?: combineOlderPrefix(prefix, live).toImmutableList()

        projectionCache[tailKey] = tailRecord
        lastProjectionSnapshot = CachedTimelineProjectionSnapshot(
            conversationId = timeline.conversationId,
            stablePrefixVersion = timeline.stablePrefixVersion,
            records = records,
            liveMessages = live,
            prefix = prefix,
            a2uiMessages = a2uiMessages,
            anyLettaServerLocalPending = anyLettaServerLocalPending,
            anyConfirmed = anyConfirmed,
            toolCardCount = toolCardCount,
            prefixToolCardCount = previous.prefixToolCardCount,
            uiSnapshot = ui,
            tailIsAssistant = tailIsAssistant,
            pendingCount = pendingCount,
            confirmedCount = confirmedCount,
        )
        val messageListChange = if (appendTail) {
            ChatMessageListChange.AppendTail
        } else {
            ChatMessageListChange.ReplaceTail
        }
        return TimelineProjection(
            ui = ui,
            tailIsAssistant = tailIsAssistant,
            anyLettaServerLocalPending = anyLettaServerLocalPending,
            anyConfirmed = anyConfirmed,
            a2uiMessages = a2uiMessages,
            toolCardCount = previous.prefixToolCardCount + toolCardCount,
            eventsReused = records.size - (if (tailCached == null) 1 else 0),
            eventsProjected = if (tailCached == null) 1 else 0,
            prefixEventsChecked = 0,
            fastPath = true,
            messageListChange = messageListChange,
        )
    }

    private fun emitProjectionTelemetry(
        timeline: Timeline,
        projection: TimelineProjection,
        prefix: List<UiMessage>,
        previousState: ChatUiState,
        startedAtMs: Long,
    ) {
        // letta-mobile-yflpp DEDUPE telemetry: a no-op tick never reaches the
        // UI, so don't emit a full uiProjection.snapshot (which is what made
        // the storm look like ~20 real projections/sec in logcat). Emit a
        // throttled uiProjection.suppressed counter instead so the dedupe is
        // observable on-device (`adb logcat -s Telemetry/TimelineSync`) without
        // re-creating the spam it suppresses.
        if (projection.noChange) {
            suppressedProjectionTick++
            if (suppressedProjectionTick % SUPPRESSED_TELEMETRY_SAMPLE == 1) {
                Telemetry.event(
                    "TimelineSync",
                    "uiProjection.suppressed",
                    "conversationId" to timeline.conversationId,
                    "eventsTotal" to timeline.events.size,
                    "suppressedRunCount" to suppressedProjectionTick,
                    "toolCardCount" to projection.toolCardCount,
                    "isStreaming" to previousState.isStreaming,
                )
            }
            return
        }
        suppressedProjectionTick = 0

        projectionTelemetryTick++
        if (projection.fastPath && timeline.events.size > 128 && projectionTelemetryTick % 16 != 1) return

        Telemetry.event(
            "TimelineSync",
            "uiProjection.snapshot",
            "conversationId" to timeline.conversationId,
            "eventsTotal" to timeline.events.size,
            "eventsReused" to projection.eventsReused,
            "eventsProjected" to projection.eventsProjected,
            "prefixEventsChecked" to projection.prefixEventsChecked,
            "messageCount" to projection.ui.size,
            "prefixCount" to prefix.size,
            "toolCardCount" to projection.toolCardCount,
            "fastPath" to projection.fastPath,
            "isStreaming" to previousState.isStreaming,
            "isLoadingMessages" to previousState.isLoadingMessages,
            "isLoadingOlderMessages" to previousState.isLoadingOlderMessages,
            "isHydrating" to previousState.isLoadingMessages,
            // The timeline observer is downstream of reconcile emissions today;
            // no in-flight reconcile flag is exposed here yet. Keep the field
            // present so load-pressure telemetry has a stable schema.
            "isReconciling" to false,
            durationMs = System.currentTimeMillis() - startedAtMs,
        )
    }

    private fun combineOlderPrefix(
        prefix: List<UiMessage>,
        live: List<UiMessage>,
    ): List<UiMessage> {
        val seenIds = HashSet<String>(live.size + prefix.size)
        val combined = ArrayList<UiMessage>(live.size + prefix.size)
        for (m in prefix) if (seenIds.add(m.id)) combined.add(m)
        for (m in live) if (seenIds.add(m.id)) combined.add(m)
        return combined
    }

    private fun TimelineEvent.projectForCacheRecord(key: TimelineProjectionKey): CachedTimelineProjectionEvent {
        val extractedA2uiMessages = mutableListOf<A2uiMessage>()
        val uiMessage = timelineEventToUiMessage(this)
            ?.extractA2uiHistoryInto(extractedA2uiMessages)
            ?.takeUnless { it.isEmptyAfterA2uiExtraction() }
        return CachedTimelineProjectionEvent(
            key = key,
            event = this,
            uiMessage = uiMessage,
            a2uiMessages = extractedA2uiMessages.toList(),
            toolCardCount = uiMessage?.toolCardCount() ?: 0,
            isLettaServerLocalPending = isLettaServerLocalPending(),
            isConfirmedVisible = this is TimelineEvent.Confirmed && uiMessage != null,
        )
    }

    private fun TimelineEvent.projectionKey(): TimelineProjectionKey = when (this) {
        is TimelineEvent.Confirmed -> TimelineProjectionKey("confirmed", serverId, messageType.name)
        is TimelineEvent.Local -> TimelineProjectionKey("local", otid, messageType.name)
    }

    private fun UiMessage.toolCardCount(): Int =
        toolCalls?.size ?: if (role == "tool" || generatedUi != null || approvalRequest != null || approvalResponse != null) 1 else 0

    private fun TimelineEvent.isLettaServerLocalPending(): Boolean =
        this is TimelineEvent.Local && deliveryState == DeliveryState.SENDING

    private fun UiMessage.extractA2uiHistoryInto(out: MutableList<A2uiMessage>): UiMessage {
        if (role != "assistant" || content.isBlank()) return this
        val extraction = A2uiHistoryExtractor.extract(content)
        if (extraction.messages.isEmpty()) return this
        out += extraction.messages
        return copy(content = extraction.content)
    }

    private fun UiMessage.isEmptyAfterA2uiExtraction(): Boolean =
        content.isBlank() &&
            generatedUi == null &&
            toolCalls.isNullOrEmpty() &&
            approvalRequest == null &&
            approvalResponse == null &&
            attachments.isEmpty()

    private companion object {
        // Sample rate for the deduped no-op telemetry counter so a long
        // suppressed run doesn't itself become spam.
        const val SUPPRESSED_TELEMETRY_SAMPLE = 32
    }
}

/**
 * Output of [ChatTimelineProjector.project]: the ordered UI message list, the
 * cheap [ChatMessageListChange] hint, extracted A2UI history, and the aggregate
 * flags a chat surface uses to derive streaming/typing presentation.
 */
data class TimelineProjection(
    val ui: ImmutableList<UiMessage>,
    val tailIsAssistant: Boolean,
    val anyLettaServerLocalPending: Boolean,
    val anyConfirmed: Boolean,
    val a2uiMessages: List<A2uiMessage>,
    val toolCardCount: Int,
    val eventsReused: Int,
    val eventsProjected: Int,
    val prefixEventsChecked: Int,
    val fastPath: Boolean,
    val messageListChange: ChatMessageListChange,
    /**
     * letta-mobile-yflpp: true when this projection is byte-identical to the
     * previously applied one (a no-op streaming tick). The collector skips the
     * uiState write and emits suppressed telemetry instead of a full
     * uiProjection.snapshot.
     */
    val noChange: Boolean = false,
)

private data class CachedTimelineProjectionSnapshot(
    val conversationId: String,
    val stablePrefixVersion: Long,
    val records: List<CachedTimelineProjectionEvent>,
    val liveMessages: List<UiMessage>,
    val prefix: List<UiMessage>,
    val a2uiMessages: List<A2uiMessage>,
    val anyLettaServerLocalPending: Boolean,
    val anyConfirmed: Boolean,
    val toolCardCount: Int,
    val prefixToolCardCount: Int,
    // letta-mobile-yflpp: cache the already-combined immutable UI list and tail
    // flag so a deduped no-op tick can return the previous projection without
    // re-running combineOlderPrefix/toImmutableList (O(history)).
    val uiSnapshot: ImmutableList<UiMessage>? = null,
    val tailIsAssistant: Boolean = false,
    // letta-mobile-yflpp: counts of pending/confirmed records so the fast path
    // can keep aggregate booleans O(delta) instead of re-scanning all records
    // per streaming tick.
    val pendingCount: Int = 0,
    val confirmedCount: Int = 0,
)

private data class TimelineProjectionKey(
    val source: String,
    val id: String,
    val type: String,
)

private data class CachedTimelineProjectionEvent(
    val key: TimelineProjectionKey,
    val event: TimelineEvent,
    val uiMessage: UiMessage?,
    val a2uiMessages: List<A2uiMessage>,
    val toolCardCount: Int,
    val isLettaServerLocalPending: Boolean,
    val isConfirmedVisible: Boolean,
) {
    /**
     * letta-mobile-yflpp: true when this record renders identically to [other] —
     * same projected UiMessage, same extracted a2ui messages, and same
     * projection-relevant flags. Deliberately ignores the raw [TimelineEvent]
     * (and thus non-rendered fields like seqId/liveCursor) so a streaming tick
     * that changed nothing visible is treated as a no-op. UiMessage and
     * A2uiMessage are data classes, so == is a deep content compare.
     */
    fun rendersSameAs(other: CachedTimelineProjectionEvent): Boolean =
        key == other.key &&
            uiMessage == other.uiMessage &&
            toolCardCount == other.toolCardCount &&
            isLettaServerLocalPending == other.isLettaServerLocalPending &&
            isConfirmedVisible == other.isConfirmedVisible &&
            a2uiMessages == other.a2uiMessages
}
