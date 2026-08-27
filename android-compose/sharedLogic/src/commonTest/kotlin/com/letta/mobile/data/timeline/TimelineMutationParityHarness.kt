package com.letta.mobile.data.timeline

import kotlinx.collections.immutable.persistentMapOf

/** Test-only semantic journal. It deliberately owns no production state. */
internal class TimelineMutationParityOwner(
    initial: TimelineReducerState,
) {
    private var state = initial
    private var closed = false
    private val queued = ArrayDeque<QueuedMutation>()
    val journal = mutableListOf<TimelineMutationJournalEntry>()

    fun enqueue(mutation: TimelineMutation): TestMutationAck {
        val ack = TestMutationAck()
        if (closed) {
            ack.fail("owner closed")
        } else {
            queued.addLast(QueuedMutation(mutation, ack))
        }
        return ack
    }

    fun drainOne(): TimelineMutationJournalEntry? {
        val queuedMutation = queued.removeFirstOrNull() ?: return null
        val mutation = queuedMutation.mutation
        val staleSequence = mutation.sequence <= state.lastAppliedMutationSequence
        val staleGeneration = when (mutation) {
            is TimelineMutation.HydrateSnapshot -> mutation.generation < state.hydrateGeneration
            is TimelineMutation.ReconcileSnapshot -> mutation.generation < state.highestRequestedReconcileGeneration
            else -> false
        }
        if (staleSequence || staleGeneration) {
            queuedMutation.ack.reject(if (staleSequence) "stale sequence" else "stale generation")
            return record(mutation, emptyList(), queuedMutation.ack)
        }

        val reduction = reduce(mutation)
        state = reduction.next.copy(lastAppliedMutationSequence = mutation.sequence)
        val orderedEffects = reduction.effects.map(::effectFingerprint)
        queuedMutation.ack.complete()
        return record(mutation, orderedEffects, queuedMutation.ack)
    }

    fun drain() {
        while (drainOne() != null) Unit
    }

    fun close(reason: String = "owner closed") {
        closed = true
        while (queued.isNotEmpty()) queued.removeFirst().ack.fail(reason)
    }

    fun currentState(): TimelineReducerState = state

    private fun reduce(mutation: TimelineMutation): TimelineReduction = when (mutation) {
        is TimelineMutation.LocalAppend -> reduceLocalAppend(
            state,
            LocalAppendPayload(mutation.pending.otid, mutation.pending.content, mutation.pending.attachments, mutation.sentAt),
        )
        is TimelineMutation.RetryLocal -> reduceRetryLocal(state, mutation.otid)
        is TimelineMutation.MarkLocalSent -> reduceMarkLocalSent(state, mutation.otid)
        is TimelineMutation.MarkLocalFailed -> reduceMarkLocalFailed(state, mutation.otid)
        is TimelineMutation.StreamFrame -> {
            val output = reduceStreamFrame(
                TimelineReducerInput(state.timeline, mutation.message, state.pendingToolReturnsByCallId, "parity-test")
            )
            val effects = buildList {
                output.emittedEvents.forEach { add(TimelineReductionEffect.EmitSyncEvent(it)) }
                output.notification?.let { add(TimelineReductionEffect.Notify(it)) }
            }.toTimelinePersistentList()
            TimelineReduction(
                state.copy(
                    timeline = output.next,
                    pendingToolReturnsByCallId = output.updatedPendingToolReturnsByCallId,
                ),
                effects,
                if (output.next == state.timeline && output.updatedPendingToolReturnsByCallId == state.pendingToolReturnsByCallId) {
                    TimelineReductionResult.NoChange
                } else {
                    TimelineReductionResult.Changed(TimelineChangeKind.RECONCILED)
                },
            )
        }
        is TimelineMutation.SnapshotEnrichment -> reduceSnapshotEnrichment(state, mutation.messages)
        is TimelineMutation.HydrateSnapshot -> {
            val hydrated = TimelineHydrationReducer.reduce(
                state.timeline.conversationId,
                normalizeHydratedMessageOrder(mutation.messages),
                state.timeline,
                state.timeline,
                emptyList(),
            )
            TimelineReduction(
                state.copy(timeline = hydrated.timeline, hydrateGeneration = mutation.generation),
                result = TimelineReductionResult.Changed(TimelineChangeKind.RECONCILED),
            )
        }
        is TimelineMutation.ReconcileSnapshot -> {
            val enriched = reduceSnapshotEnrichment(state, mutation.messages)
            val replaySafeTimeline = enriched.next.timeline.copy(
                events = enriched.next.timeline.events.filterNot { event ->
                    event is TimelineEvent.Confirmed && state.timeline.abandonedAssistantFragmentSuppressions.any { suppression ->
                        (suppression.serverId != null && suppression.serverId == event.serverId) ||
                            (suppression.runId != null && suppression.runId == event.runId &&
                                suppression.contentFingerprint == event.content.trim().take(256))
                    }
                }.toTimelinePersistentList(),
            )
            TimelineReduction(
                enriched.next.copy(
                    timeline = replaySafeTimeline,
                    highestRequestedReconcileGeneration = maxOf(state.highestRequestedReconcileGeneration, mutation.generation),
                    highestAppliedReconcileGeneration = mutation.generation,
                ),
                result = TimelineReductionResult.Changed(TimelineChangeKind.RECONCILED),
            )
        }
        is TimelineMutation.CleanupAbandonedFragments -> reduceCleanup(
            state,
            mutation.runId,
            mutation.turnId,
            mutation.reason,
            mutation.candidateRunIds,
        )
        is TimelineMutation.LifecycleReset -> TimelineReduction(
            state.copy(lifecycleEpoch = mutation.epoch, pendingToolReturnsByCallId = persistentMapOf()),
            result = TimelineReductionResult.Changed(TimelineChangeKind.RECONCILED),
        )
    }

    private fun record(
        mutation: TimelineMutation,
        effects: List<String>,
        ack: TestMutationAck,
    ): TimelineMutationJournalEntry = TimelineMutationJournalEntry(
        acceptedSequence = mutation.sequence,
        mutationFamily = mutation::class.simpleName ?: "unknown",
        state = state.semanticFingerprint(),
        orderedEffects = effects,
        ack = ack.outcome,
    ).also(journal::add)
}

internal data class TimelineMutationJournalEntry(
    val acceptedSequence: Long,
    val mutationFamily: String,
    val state: String,
    val orderedEffects: List<String>,
    val ack: TestAckOutcome,
) {
    fun trace(): List<String> = listOf("state:$state") + orderedEffects.map { "effect:$it" } + "ack:$ack"
}

internal enum class TestAckOutcome { PENDING, COMPLETED, REJECTED, FAILED }

internal class TestMutationAck {
    var outcome: TestAckOutcome = TestAckOutcome.PENDING
        private set
    var reason: String? = null
        private set

    fun complete() { outcome = TestAckOutcome.COMPLETED }
    fun reject(value: String) { outcome = TestAckOutcome.REJECTED; reason = value }
    fun fail(value: String) { outcome = TestAckOutcome.FAILED; reason = value }
}

private data class QueuedMutation(val mutation: TimelineMutation, val ack: TestMutationAck)

internal fun TimelineReducerState.semanticFingerprint(): String = buildString {
    append("events=[")
    append(timeline.events.joinToString(";") { event ->
        when (event) {
            is TimelineEvent.Local -> "local:${event.otid}:${event.content}:${event.deliveryState}:${event.position}"
            is TimelineEvent.Confirmed -> "confirmed:${event.serverId}:${event.otid}:${event.messageType}:${event.content}:${event.position}:" +
                "${event.runId}:${event.seqId}:${event.approvalDecided}:${event.approvalDecision}:" +
                event.toolReturnContentByCallId.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        }
    })
    append("];cursors=${timeline.liveCursor},${timeline.backfillCursor}")
    append(";retention=${timeline.releasedOlderCount}:${timeline.stablePrefixVersion}:${timeline.visibleRevision}")
    append(";suppressions=${timeline.abandonedAssistantFragmentSuppressions.sortedBy { it.toString() }}")
    append(";pending=${pendingToolReturnsByCallId.entries.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value.id}:${it.value.toolReturn.funcResponse}" }}")
    append(";generations=$hydrateGeneration,$highestRequestedReconcileGeneration,$highestAppliedReconcileGeneration")
    append(";epoch=$lifecycleEpoch;freshness=$freshnessSequence;sequence=$lastAppliedMutationSequence")
}

private fun effectFingerprint(effect: TimelineReductionEffect): String = when (effect) {
    is TimelineReductionEffect.EmitSyncEvent -> "event:${effect.event}"
    is TimelineReductionEffect.Notify -> "notification:${effect.notification.serverId}:${effect.notification.messageType}:${effect.notification.contentPreview}"
    is TimelineReductionEffect.Send -> "send:${effect.pending.otid}:${effect.pending.content}"
    is TimelineReductionEffect.PersistPendingLocal -> "persist:${effect.pending.otid}:${effect.sentAt}"
    is TimelineReductionEffect.DeletePendingLocal -> "delete:${effect.otid}"
    is TimelineReductionEffect.AdvanceCursor -> "cursor:${effect.cursor}"
}
