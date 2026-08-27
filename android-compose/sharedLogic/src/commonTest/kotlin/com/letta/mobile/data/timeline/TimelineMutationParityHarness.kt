package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageContentPart

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
            return record(mutation, acceptedSequence = null, reduction = null, queuedMutation.ack)
        }

        val reduction = reduce(mutation)
        state = reduction.next.copy(lastAppliedMutationSequence = mutation.sequence)
        queuedMutation.ack.complete()
        return record(mutation, acceptedSequence = mutation.sequence, reduction, queuedMutation.ack)
    }

    fun drain() {
        while (queued.isNotEmpty()) drainOne()
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
            val next = state.copy(
                timeline = output.next,
                pendingToolReturnsByCallId = output.updatedPendingToolReturnsByCallId,
            )
            TimelineReduction(
                next,
                effects,
                if (next == state) TimelineReductionResult.NoChange
                else TimelineReductionResult.Changed(TimelineChangeKind.RECONCILED),
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
            val next = state.copy(timeline = hydrated.timeline, hydrateGeneration = mutation.generation)
            TimelineReduction(
                next,
                result = if (next == state) TimelineReductionResult.NoChange
                else TimelineReductionResult.Changed(TimelineChangeKind.RECONCILED),
            )
        }
        is TimelineMutation.ReconcileSnapshot -> {
            val enriched = reduceSnapshotEnrichment(state, mutation.messages)
            val merged = enriched.next.timeline.mergeServerMessages(mutation.messages).first
            val next = enriched.next.copy(
                timeline = merged,
                highestRequestedReconcileGeneration = maxOf(state.highestRequestedReconcileGeneration, mutation.generation),
                highestAppliedReconcileGeneration = mutation.generation,
            )
            TimelineReduction(
                next,
                result = if (next == state) TimelineReductionResult.NoChange
                else TimelineReductionResult.Changed(TimelineChangeKind.RECONCILED),
            )
        }
        is TimelineMutation.CleanupAbandonedFragments -> reduceCleanup(
            state,
            mutation.runId,
            mutation.turnId,
            mutation.reason,
            mutation.candidateRunIds,
        )
        is TimelineMutation.LifecycleReset -> {
            val next = state.copy(lifecycleEpoch = mutation.epoch)
            TimelineReduction(
                next,
                result = if (next == state) TimelineReductionResult.NoChange
                else TimelineReductionResult.Changed(TimelineChangeKind.RECONCILED),
            )
        }
    }

    private fun record(
        mutation: TimelineMutation,
        acceptedSequence: Long?,
        reduction: TimelineReduction?,
        ack: TestMutationAck,
    ): TimelineMutationJournalEntry = TimelineMutationJournalEntry(
        attemptedSequence = mutation.sequence,
        acceptedSequence = acceptedSequence,
        mutationFamily = mutation::class.simpleName ?: "unknown",
        state = state.semanticFingerprint(),
        orderedEffects = reduction?.effects?.map(::effectFingerprint).orEmpty(),
        result = reduction?.result,
        ack = ack.outcome,
    ).also(journal::add)
}

internal data class TimelineMutationJournalEntry(
    val attemptedSequence: Long,
    val acceptedSequence: Long?,
    val mutationFamily: String,
    val state: String,
    val orderedEffects: List<String>,
    val result: TimelineReductionResult?,
    val ack: TestAckOutcome,
) {
    fun trace(): List<String> = buildList {
        add("state:$state")
        orderedEffects.forEach(::add)
        add("ack:$ack")
    }
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
            is TimelineEvent.Local -> "local:${event.otid}:${event.content}:${event.deliveryState}:${event.position}:" +
                "${event.sentAt}:${event.attachments.stableFingerprint()}"
            is TimelineEvent.Confirmed -> "confirmed:${event.serverId}:${event.otid}:${event.messageType}:${event.content}:${event.position}:" +
                "${event.date}:${event.runId}:${event.stepId}:${event.attachments.stableFingerprint()}:" +
                "${event.seqId}:${event.approvalDecided}:${event.approvalDecision}:" +
                "returns=${event.toolReturnContentByCallId.stableEntries()}:" +
                "errors=${event.toolReturnIsErrorByCallId.stableEntries()}:" +
                "truncations=${event.toolReturnTruncationByCallId.stableEntries()}"
        }
    })
    append("];cursors=${timeline.liveCursor},${timeline.backfillCursor}")
    append(";retention=${timeline.releasedOlderCount}:${timeline.stablePrefixVersion}:${timeline.visibleRevision}")
    append(";suppressions=")
    append(timeline.abandonedAssistantFragmentSuppressions
        .sortedWith(compareBy({ it.serverId ?: "" }, { it.runId ?: "" }, { it.contentFingerprint }))
        .joinToString(",") { "${it.serverId ?: "<null>"}:${it.runId ?: "<null>"}:${it.contentFingerprint}" })
    append(";residentOtids=${timeline.residentOtids.sorted().joinToString(",")}")
    append(";residentServerIds=${timeline.events.filterIsInstance<TimelineEvent.Confirmed>().map { it.serverId }.sorted().joinToString(",")}")
    append(";pending=")
    append(pendingToolReturnsByCallId.entries.sortedBy { it.key }.joinToString { (callId, message) ->
        listOf(callId, message.id, message.toolReturn.funcResponse.orEmpty()).joinToString(prefix = "[", postfix = "]") { it.lengthPrefixed() }
    })
    append(";generations=$hydrateGeneration,$highestRequestedReconcileGeneration,$highestAppliedReconcileGeneration")
    append(";epoch=$lifecycleEpoch;freshness=$freshnessSequence;sequence=$lastAppliedMutationSequence")
}

private fun List<MessageContentPart.Image>.stableFingerprint(): String =
    joinToString(",") { "${it.mediaType}:${it.base64}" }

private fun Map<*, *>.stableEntries(): String =
    entries.sortedBy { it.key.toString() }.joinToString { entry ->
        listOf(entry.key.toString(), entry.value.toString()).joinToString(prefix = "[", postfix = "]") { it.lengthPrefixed() }
    }

private fun String.lengthPrefixed(): String = "${length}:$this"

private fun effectFingerprint(effect: TimelineReductionEffect): String = when (effect) {
    is TimelineReductionEffect.EmitSyncEvent -> "sync-event:${effect.event}"
    is TimelineReductionEffect.Notify -> "notification:${effect.notification.serverId}:${effect.notification.messageType}:${effect.notification.contentPreview}"
    is TimelineReductionEffect.Send -> "effect:send:${effect.pending.otid}:${effect.pending.content}"
    is TimelineReductionEffect.PersistPendingLocal -> "effect:persist:${effect.pending.otid}:${effect.sentAt}"
    is TimelineReductionEffect.DeletePendingLocal -> "effect:delete:${effect.otid}"
    is TimelineReductionEffect.AdvanceCursor -> "effect:cursor:${effect.cursor}"
}
