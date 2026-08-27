package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.ToolReturnMessage
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

/** Platform-neutral state contract for a future single-owner timeline reducer. */
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

/** Payload-only names for timeline mutation families; no variant performs work. */
sealed interface TimelineMutation {
    val sequence: Long

    data class LocalAppend(
        override val sequence: Long,
        val pending: PendingSend,
        val sentAt: TimelineInstant,
    ) : TimelineMutation

    data class RetryLocal(override val sequence: Long, val otid: String) : TimelineMutation
    data class MarkLocalSent(override val sequence: Long, val otid: String) : TimelineMutation
    data class MarkLocalFailed(override val sequence: Long, val otid: String) : TimelineMutation
    data class StreamFrame(override val sequence: Long, val message: LettaMessage) : TimelineMutation
    data class SnapshotEnrichment(override val sequence: Long, val messages: List<LettaMessage>) : TimelineMutation
    data class HydrateSnapshot(override val sequence: Long, val generation: Long, val messages: List<LettaMessage>) : TimelineMutation
    data class ReconcileSnapshot(override val sequence: Long, val generation: Long, val messages: List<LettaMessage>) : TimelineMutation
    data class CleanupAbandonedFragments(
        override val sequence: Long,
        val runId: String?,
        val turnId: String?,
        val reason: String,
        val candidateRunIds: Set<String> = emptySet(),
    ) : TimelineMutation
    data class LifecycleReset(override val sequence: Long, val epoch: Long) : TimelineMutation
}

sealed interface TimelineReductionEffect {
    data class EmitSyncEvent(val event: TimelineSyncEvent) : TimelineReductionEffect
    data class Notify(val notification: PendingIngestNotification) : TimelineReductionEffect
    data class Send(val pending: PendingSend) : TimelineReductionEffect
    data class PersistPendingLocal(val pending: PendingSend, val sentAt: TimelineInstant) : TimelineReductionEffect
    data class DeletePendingLocal(val otid: String) : TimelineReductionEffect
    data class AdvanceCursor(val cursor: String) : TimelineReductionEffect
}

sealed interface TimelineReductionResult {
    val changed: Boolean

    data object NoChange : TimelineReductionResult { override val changed: Boolean = false }
    data class Changed(val kind: TimelineChangeKind) : TimelineReductionResult { override val changed: Boolean = true }
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
    val pure = reconcileAfterSendSnapshot(state.timeline, otid, serverMessages)
    val effects = buildList {
        pure.result.confirmedServerId?.let { add(TimelineReductionEffect.EmitSyncEvent(TimelineSyncEvent.LocalConfirmed(otid, it))) }
        if (pure.result.shouldDeletePendingLocal) add(TimelineReductionEffect.DeletePendingLocal(otid))
        serverMessages.lastOrNull()?.id?.let { add(TimelineReductionEffect.AdvanceCursor(it)) }
    }.toTimelinePersistentList()
    return if (pure.timeline == state.timeline) unchanged(state)
    else TimelineReduction(state.copy(timeline = pure.timeline), effects, TimelineReductionResult.Changed(TimelineChangeKind.RECONCILED))
}

private fun deliveryReduction(state: TimelineReducerState, timeline: Timeline, kind: TimelineChangeKind): TimelineReduction =
    if (timeline == state.timeline) unchanged(state) else changed(state.copy(timeline = timeline), kind)

private fun unchanged(state: TimelineReducerState) = TimelineReduction(state, result = TimelineReductionResult.NoChange)

private fun changed(
    state: TimelineReducerState,
    kind: TimelineChangeKind,
    vararg effects: TimelineReductionEffect,
) = TimelineReduction(state, effects.toList().toTimelinePersistentList(), TimelineReductionResult.Changed(kind))
