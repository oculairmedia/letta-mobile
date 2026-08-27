package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolReturnMessage
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/** Platform-neutral immutable state owned by [TimelineProcessor]. */
data class TimelineReducerState(
    val timeline: Timeline,
    val pendingToolReturnsByCallId: PersistentMap<String, ToolReturnMessage> = persistentMapOf(),
    val lifecycleEpoch: Long = 0L,
    val lastAppliedMutationSequence: Long = 0L,
    val hydrateGeneration: Long = 0L,
    val highestRequestedReconcileGeneration: Long = 0L,
    val highestAppliedReconcileGeneration: Long = 0L,
    val freshnessSequence: Long = 0L,
)

/** Payload-only mutation families. Ordering is assigned internally by [TimelineProcessor]. */
sealed interface TimelineMutation {
    data class LocalAppend(
        val pending: PendingSend,
        val sentAt: TimelineInstant,
        val mode: TimelineLocalAppendMode = TimelineLocalAppendMode.SEND,
    ) : TimelineMutation

    data class RetryLocal(val otid: String) : TimelineMutation
    data class MarkLocalSent(val otid: String) : TimelineMutation
    data class MarkLocalFailed(val otid: String) : TimelineMutation
    data class StreamFrame(
        val message: LettaMessage,
        val agentId: String? = null,
    ) : TimelineMutation
    data class SnapshotEnrichment(val messages: List<LettaMessage>) : TimelineMutation
    data class HydrateSnapshot(
        val generation: Long,
        val messages: List<LettaMessage>,
        val timelineBeforeFetch: Timeline,
        val diskRecords: List<PendingLocalRecord>,
        val cursorSequence: Long? = null,
    ) : TimelineMutation
    data class ReconcileSnapshot(val generation: Long, val messages: List<LettaMessage>) : TimelineMutation
    data class RecentMessagesSnapshot(
        val generation: Long,
        val freshnessSequence: Long,
        val messages: List<LettaMessage>,
    ) : TimelineMutation
    data class ReconcileAfterSendSnapshot(
        val otid: String,
        val messages: List<LettaMessage>,
    ) : TimelineMutation
    data class CleanupAbandonedFragments(
        val runId: String?,
        val turnId: String?,
        val reason: String,
        val candidateRunIds: Set<String> = emptySet(),
    ) : TimelineMutation
    data class LifecycleReset(val epoch: Long) : TimelineMutation
}

/** Local append variants currently migrated to the processor. */
enum class TimelineLocalAppendMode {
    /** Append, enqueue the transport send, then emit LocalAppended. */
    SEND,

    /** The caller queues transport separately; append only for same-frame UI visibility. */
    OPTIMISTIC,

    /** Append a local observed from the external transport and emit LocalAppended. */
    EXTERNAL_TRANSPORT,
}

sealed interface TimelineReductionEffect {
    data class EmitSyncEvent(val event: TimelineSyncEvent) : TimelineReductionEffect
    data class Notify(val notification: PendingIngestNotification) : TimelineReductionEffect
    data class Send(val pending: PendingSend) : TimelineReductionEffect
    data class PersistPendingLocal(val pending: PendingSend, val sentAt: TimelineInstant) : TimelineReductionEffect
    data class DeletePendingLocal(val otid: String) : TimelineReductionEffect
    /** Persist the SSE resume sequence; this is distinct from Timeline.liveCursor. */
    data class RecordStreamSequence(val sequence: Long) : TimelineReductionEffect
    /** Repair the SSE resume sequence from an accepted hydration snapshot. */
    data class RepairHydrationCursor(val sequence: Long) : TimelineReductionEffect
    data class AdvanceCursor(val cursor: String) : TimelineReductionEffect
}

sealed interface TimelineReductionResult {
    val changed: Boolean

    data object NoChange : TimelineReductionResult { override val changed: Boolean = false }
    data class Changed(val kind: TimelineChangeKind) : TimelineReductionResult { override val changed: Boolean = true }
    data class Hydrated(
        val visibleEventCount: Int,
        override val changed: Boolean,
    ) : TimelineReductionResult
    data class RecentMessagesApplied(
        val appended: Int,
        override val changed: Boolean,
    ) : TimelineReductionResult
    data class ReconcileAfterSendApplied(
        val result: ReconcileAfterSendResult,
        override val changed: Boolean,
    ) : TimelineReductionResult
}

enum class TimelineChangeKind { LOCAL_APPENDED, LOCAL_RETRIED, LOCAL_SENT, LOCAL_FAILED, SNAPSHOT_ENRICHED, CLEANED, RECONCILED }

data class TimelineReduction(
    val next: TimelineReducerState,
    val effects: PersistentList<TimelineReductionEffect> = persistentListOf(),
    val result: TimelineReductionResult,
)

data class LocalAppendPayload(
    val otid: String,
    val content: String,
    val attachments: PersistentList<MessageContentPart.Image> = persistentListOf(),
    val sentAt: TimelineInstant,
)

fun reduceLocalAppend(state: TimelineReducerState, payload: LocalAppendPayload): TimelineReduction {
    if (state.timeline.findByOtid(payload.otid) != null) return unchanged(state)
    val local = TimelineEvent.Local(
        position = state.timeline.nextLocalPosition(),
        otid = payload.otid,
        content = payload.content,
        role = Role.USER,
        sentAt = payload.sentAt,
        deliveryState = DeliveryState.SENDING,
        attachments = payload.attachments,
    )
    val pending = PendingSend(payload.otid, payload.content, payload.attachments)
    return changed(
        state.copy(timeline = state.timeline.append(local)),
        TimelineChangeKind.LOCAL_APPENDED,
        TimelineReductionEffect.Send(pending),
        TimelineReductionEffect.EmitSyncEvent(TimelineSyncEvent.LocalAppended(payload.otid)),
    )
}

fun reduceLocalAppend(
    state: TimelineReducerState,
    payload: LocalAppendPayload,
    mode: TimelineLocalAppendMode,
): TimelineReduction {
    val reduction = reduceLocalAppend(state, payload)
    if (!reduction.result.changed || mode == TimelineLocalAppendMode.SEND) return reduction
    val effects = when (mode) {
        TimelineLocalAppendMode.SEND -> reduction.effects
        TimelineLocalAppendMode.OPTIMISTIC -> persistentListOf()
        TimelineLocalAppendMode.EXTERNAL_TRANSPORT -> persistentListOf(
            TimelineReductionEffect.EmitSyncEvent(TimelineSyncEvent.LocalAppended(payload.otid)),
        )
    }
    return reduction.copy(effects = effects)
}

fun reduceRetryLocal(state: TimelineReducerState, otid: String): TimelineReduction {
    val existing = state.timeline.findByOtid(otid) as? TimelineEvent.Local ?: return unchanged(state)
    if (existing.deliveryState != DeliveryState.FAILED) return unchanged(state)
    val persisted = state.timeline.events.map {
        if (it.otid == otid && it is TimelineEvent.Local) it.copy(deliveryState = DeliveryState.SENDING) else it
    }.toTimelinePersistentList()
    val nextTimeline = state.timeline.copy(events = persisted, stablePrefixVersion = persisted.stablePrefixFingerprint())
    return changed(
        state.copy(timeline = nextTimeline),
        TimelineChangeKind.LOCAL_RETRIED,
        TimelineReductionEffect.Send(PendingSend(otid, existing.content, existing.attachments)),
    )
}

fun reduceMarkLocalSent(state: TimelineReducerState, otid: String): TimelineReduction =
    deliveryReduction(state, state.timeline.markSent(otid), TimelineChangeKind.LOCAL_SENT)

fun reduceMarkLocalFailed(state: TimelineReducerState, otid: String): TimelineReduction =
    deliveryReduction(state, state.timeline.markFailed(otid), TimelineChangeKind.LOCAL_FAILED)

fun reduceSnapshotEnrichment(state: TimelineReducerState, snapshot: List<LettaMessage>): TimelineReduction {
    val nextTimeline = enrichTimelineFromSnapshot(state.timeline, snapshot)
    return if (nextTimeline == state.timeline) unchanged(state)
    else changed(state.copy(timeline = nextTimeline), TimelineChangeKind.SNAPSHOT_ENRICHED)
}

fun enrichTimelineFromSnapshot(timeline: Timeline, snapshot: List<LettaMessage>): Timeline {
    val evidence = approvalTimelineEvidence(snapshot)
    if (evidence.responsesByRequestId.isEmpty() && evidence.returnsByCallId.isEmpty()) return timeline
    val newEvents = timeline.events.map { ev ->
        if (ev !is TimelineEvent.Confirmed || ev.messageType != TimelineMessageType.TOOL_CALL) return@map ev
        val matchingReturns = ev.matchingToolReturns(evidence)
        val matchingReturn = matchingReturns.firstOrNull()?.second
        val byResponse = ev.hasAnyApprovalResponse(evidence)
        val byReturn = if (ev.approvalRequestId == null) matchingReturns.isNotEmpty() else ev.allApprovalCallsReturned(matchingReturns)
        if (matchingReturn == null && !byResponse && !byReturn) return@map ev
        val fold = foldToolReturnBodies(ev.toolReturnContentByCallId, ev.toolReturnTruncationByCallId, matchingReturns)
        val errors = ev.toolReturnIsErrorByCallId + matchingReturns.associate { (id, value) -> id to (value.isErr == true || value.status == "error") }
        ev.copy(
            approvalDecided = byResponse || byReturn || ev.approvalDecided,
            approvalDecision = ev.approvalOutcomeFromEvidence(evidence) ?: ev.approvalDecision,
            toolReturnContent = matchingReturns.firstOrNull()?.first?.let { fold.contentByCallId[it] } ?: ev.toolReturnContent,
            toolReturnIsError = matchingReturn?.let { it.isErr == true || it.status == "error" } ?: ev.toolReturnIsError,
            toolReturnContentByCallId = fold.contentByCallId.toTimelinePersistentMap(),
            toolReturnIsErrorByCallId = errors.toTimelinePersistentMap(),
            toolReturnTruncationByCallId = fold.truncationByCallId.toTimelinePersistentMap(),
        )
    }
    if (newEvents == timeline.events) return timeline
    val persisted = newEvents.toTimelinePersistentList()
    return timeline.copy(events = persisted, stablePrefixVersion = persisted.stablePrefixFingerprint())
}

fun reduceCleanup(
    state: TimelineReducerState,
    runId: String?,
    turnId: String?,
    reason: String,
    candidateRunIds: Set<String> = emptySet(),
): TimelineReduction {
    val cleanup = state.timeline.cleanupAbandonedAssistantFragments(runId, turnId, reason, candidateRunIds)
    if (cleanup.timeline == state.timeline) return unchanged(state)
    return changed(state.copy(timeline = cleanup.timeline), TimelineChangeKind.CLEANED)
}

fun reducePostSendReconcile(
    state: TimelineReducerState,
    otid: String,
    serverMessages: List<LettaMessage>,
): TimelineReduction {
    val reconciled = reconcileAfterSendSnapshot(state.timeline, otid, serverMessages)
    // Enrich tool calls before publishing the snapshot so every after-send
    // observer sees one complete, atomically reconciled timeline.
    val enrichedTimeline = enrichTimelineFromSnapshot(reconciled.timeline, serverMessages)
    val next = state.copy(timeline = enrichedTimeline)
    val effects = buildList {
        reconciled.result.confirmedServerId?.let {
            add(TimelineReductionEffect.EmitSyncEvent(TimelineSyncEvent.LocalConfirmed(otid, it)))
        }
        if (reconciled.result.shouldDeletePendingLocal) add(TimelineReductionEffect.DeletePendingLocal(otid))
        serverMessages.lastOrNull()?.id?.let { add(TimelineReductionEffect.AdvanceCursor(it)) }
    }.toTimelinePersistentList()
    return TimelineReduction(
        next = next,
        effects = effects,
        result = TimelineReductionResult.ReconcileAfterSendApplied(
            result = reconciled.result,
            changed = next != state,
        ),
    )
}

private fun deliveryReduction(state: TimelineReducerState, timeline: Timeline, kind: TimelineChangeKind): TimelineReduction =
    if (timeline == state.timeline) unchanged(state) else changed(state.copy(timeline = timeline), kind)

private fun unchanged(state: TimelineReducerState) = TimelineReduction(state, result = TimelineReductionResult.NoChange)

private fun changed(
    state: TimelineReducerState,
    kind: TimelineChangeKind,
    vararg effects: TimelineReductionEffect,
) = TimelineReduction(state, effects.toList().toTimelinePersistentList(), TimelineReductionResult.Changed(kind))

/** Production reducer used by both [TimelineProcessor] and the parity harness. */
fun reduceProductionMutation(state: TimelineReducerState, mutation: TimelineMutation): TimelineReduction = when (mutation) {
    is TimelineMutation.LocalAppend -> reduceLocalAppend(
        state,
        LocalAppendPayload(
            mutation.pending.otid,
            mutation.pending.content,
            mutation.pending.attachments,
            mutation.sentAt,
        ),
        mutation.mode,
    )
    is TimelineMutation.RetryLocal -> reduceRetryLocal(state, mutation.otid)
    is TimelineMutation.MarkLocalSent -> reduceMarkLocalSent(state, mutation.otid)
    is TimelineMutation.MarkLocalFailed -> reduceMarkLocalFailed(state, mutation.otid)
    is TimelineMutation.StreamFrame -> reduceStreamMutation(state, mutation)
    is TimelineMutation.SnapshotEnrichment -> reduceSnapshotEnrichment(state, mutation.messages)
    is TimelineMutation.HydrateSnapshot -> reduceHydrateMutation(state, mutation)
    is TimelineMutation.ReconcileSnapshot -> reduceReconcileMutation(state, mutation)
    is TimelineMutation.RecentMessagesSnapshot -> reduceRecentMessagesMutation(state, mutation)
    is TimelineMutation.ReconcileAfterSendSnapshot -> reducePostSendReconcile(state, mutation.otid, mutation.messages)
    is TimelineMutation.CleanupAbandonedFragments -> reduceCleanup(
        state,
        mutation.runId,
        mutation.turnId,
        mutation.reason,
        mutation.candidateRunIds,
    )
    is TimelineMutation.LifecycleReset -> changedIfNeeded(state, state.copy(lifecycleEpoch = mutation.epoch))
}

private fun reduceStreamMutation(
    state: TimelineReducerState,
    mutation: TimelineMutation.StreamFrame,
): TimelineReduction {
    val output = reduceStreamFrame(
        TimelineReducerInput(
            prev = state.timeline,
            frame = mutation.message,
            pendingToolReturnsByCallId = state.pendingToolReturnsByCallId,
            source = "timeline-processor",
            agentId = mutation.agentId,
        ),
    )
    val effects = buildList {
        output.emittedEvents.forEach { add(TimelineReductionEffect.EmitSyncEvent(it)) }
        output.notification?.let { add(TimelineReductionEffect.Notify(it)) }
        mutation.message.seqId?.takeIf { it >= 0 }?.let { seq ->
            add(TimelineReductionEffect.RecordStreamSequence(seq.toLong()))
        }
    }.toTimelinePersistentList()
    val didChange = output.next != state.timeline ||
        output.updatedPendingToolReturnsByCallId != state.pendingToolReturnsByCallId
    return TimelineReduction(
        state.copy(
            timeline = output.next,
            pendingToolReturnsByCallId = output.updatedPendingToolReturnsByCallId,
        ),
        effects,
        if (didChange) TimelineReductionResult.Changed(TimelineChangeKind.RECONCILED)
        else TimelineReductionResult.NoChange,
    )
}

private fun reduceHydrateMutation(
    state: TimelineReducerState,
    mutation: TimelineMutation.HydrateSnapshot,
): TimelineReduction {
    val hydrated = TimelineHydrationReducer.reduce(
        state.timeline.conversationId,
        normalizeHydratedMessageOrder(mutation.messages),
        mutation.timelineBeforeFetch,
        state.timeline,
        mutation.diskRecords,
    )
    val next = state.copy(timeline = hydrated.timeline, hydrateGeneration = mutation.generation)
    val effects = buildList {
        mutation.cursorSequence?.let { add(TimelineReductionEffect.RepairHydrationCursor(it)) }
    }.toTimelinePersistentList()
    return TimelineReduction(
        next = next,
        effects = effects,
        result = TimelineReductionResult.Hydrated(
            visibleEventCount = hydrated.visibleEventCount,
            changed = next != state,
        ),
    )
}

private fun reduceRecentMessagesMutation(
    state: TimelineReducerState,
    mutation: TimelineMutation.RecentMessagesSnapshot,
): TimelineReduction {
    val enriched = reduceSnapshotEnrichment(state, mutation.messages)
    val merge = enriched.next.timeline.mergeServerMessages(mutation.messages)
    val next = enriched.next.copy(
        timeline = merge.first,
        highestAppliedReconcileGeneration = maxOf(state.highestAppliedReconcileGeneration, mutation.generation),
        freshnessSequence = maxOf(state.freshnessSequence, mutation.freshnessSequence),
    )
    return TimelineReduction(
        next = next,
        result = TimelineReductionResult.RecentMessagesApplied(
            appended = merge.second,
            changed = next.timeline != state.timeline,
        ),
    )
}

private fun reduceReconcileMutation(
    state: TimelineReducerState,
    mutation: TimelineMutation.ReconcileSnapshot,
): TimelineReduction {
    val enriched = reduceSnapshotEnrichment(state, mutation.messages)
    val merged = enriched.next.timeline.mergeServerMessages(mutation.messages).first
    val next = enriched.next.copy(
        timeline = merged,
        highestRequestedReconcileGeneration = maxOf(
            state.highestRequestedReconcileGeneration,
            mutation.generation,
        ),
        highestAppliedReconcileGeneration = mutation.generation,
    )
    return changedIfNeeded(state, next)
}

private fun changedIfNeeded(
    state: TimelineReducerState,
    next: TimelineReducerState,
): TimelineReduction = TimelineReduction(
    next,
    result = if (next == state) TimelineReductionResult.NoChange
    else TimelineReductionResult.Changed(TimelineChangeKind.RECONCILED),
)
