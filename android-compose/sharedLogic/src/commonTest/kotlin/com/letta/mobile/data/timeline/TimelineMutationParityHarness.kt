package com.letta.mobile.data.timeline


/** Test-only production adapter and journal. It deliberately owns no runtime state. */
internal class TimelineMutationParityOwner(
    initial: TimelineReducerState,
    private val reducer: (TimelineReducerState, TimelineMutation) -> TimelineReduction = ::reduceProductionMutation,
) {
    private var state = initial
    private var closed = false
    private val queued = ArrayDeque<QueuedMutation>()
    val journal = mutableListOf<TimelineMutationJournalEntry>()

    fun enqueue(mutation: TimelineMutation): TestMutationAck {
        val ack = TestMutationAck()
        if (closed) ack.fail("owner closed") else queued.addLast(QueuedMutation(mutation, ack))
        return ack
    }

    fun drainOne(): TimelineMutationJournalEntry? {
        val queuedMutation = queued.removeFirstOrNull() ?: return null
        val mutation = queuedMutation.mutation
        val rejection = rejectionReason(mutation, state)
        if (rejection != null) {
            queuedMutation.ack.reject(rejection)
            return record(mutation, acceptedSequence = null, reduction = null, queuedMutation.ack)
        }

        val reduction = reducer(state, mutation)
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

private fun rejectionReason(mutation: TimelineMutation, state: TimelineReducerState): String? = when {
    mutation.sequence <= state.lastAppliedMutationSequence -> "stale sequence"
    mutation is TimelineMutation.HydrateSnapshot && mutation.generation < state.hydrateGeneration -> "stale generation"
    mutation is TimelineMutation.ReconcileSnapshot && mutation.generation < state.highestRequestedReconcileGeneration -> "stale generation"
    else -> null
}

/** Calls production reducers only on the SUT side; model expectations below do not call these functions. */
internal fun reduceProductionMutation(state: TimelineReducerState, mutation: TimelineMutation): TimelineReduction = when (mutation) {
    is TimelineMutation.LocalAppend -> reduceLocalAppend(
        state,
        LocalAppendPayload(mutation.pending.otid, mutation.pending.content, mutation.pending.attachments, mutation.sentAt),
    )
    is TimelineMutation.RetryLocal -> reduceRetryLocal(state, mutation.otid)
    is TimelineMutation.MarkLocalSent -> reduceMarkLocalSent(state, mutation.otid)
    is TimelineMutation.MarkLocalFailed -> reduceMarkLocalFailed(state, mutation.otid)
    else -> reduceNonLocalMutation(state, mutation)
}

private fun reduceNonLocalMutation(state: TimelineReducerState, mutation: TimelineMutation): TimelineReduction = when (mutation) {
    is TimelineMutation.StreamFrame -> reduceStreamMutation(state, mutation)
    is TimelineMutation.SnapshotEnrichment -> reduceSnapshotEnrichment(state, mutation.messages)
    is TimelineMutation.HydrateSnapshot -> reduceHydrateMutation(state, mutation)
    is TimelineMutation.ReconcileSnapshot -> reduceReconcileMutation(state, mutation)
    is TimelineMutation.CleanupAbandonedFragments -> reduceCleanup(
        state,
        mutation.runId,
        mutation.turnId,
        mutation.reason,
        mutation.candidateRunIds,
    )
    is TimelineMutation.LifecycleReset -> changedIfNeeded(state, state.copy(lifecycleEpoch = mutation.epoch))
    else -> error("local mutation must be reduced by reduceProductionMutation")
}

private fun reduceStreamMutation(state: TimelineReducerState, mutation: TimelineMutation.StreamFrame): TimelineReduction {
    val output = reduceStreamFrame(
        TimelineReducerInput(state.timeline, mutation.message, state.pendingToolReturnsByCallId, "parity-test"),
    )
    val effects = buildList {
        output.emittedEvents.forEach { add(TimelineReductionEffect.EmitSyncEvent(it)) }
        output.notification?.let { add(TimelineReductionEffect.Notify(it)) }
    }.toTimelinePersistentList()
    val changed = output.next != state.timeline || output.updatedPendingToolReturnsByCallId != state.pendingToolReturnsByCallId
    return TimelineReduction(
        state.copy(timeline = output.next, pendingToolReturnsByCallId = output.updatedPendingToolReturnsByCallId),
        effects,
        if (changed) TimelineReductionResult.Changed(TimelineChangeKind.RECONCILED) else TimelineReductionResult.NoChange,
    )
}

private fun reduceHydrateMutation(state: TimelineReducerState, mutation: TimelineMutation.HydrateSnapshot): TimelineReduction {
    val hydrated = TimelineHydrationReducer.reduce(
        state.timeline.conversationId,
        normalizeHydratedMessageOrder(mutation.messages),
        state.timeline,
        state.timeline,
        emptyList(),
    )
    return changedIfNeeded(
        state,
        state.copy(timeline = hydrated.timeline, hydrateGeneration = mutation.generation),
    )
}

private fun reduceReconcileMutation(state: TimelineReducerState, mutation: TimelineMutation.ReconcileSnapshot): TimelineReduction {
    val enriched = reduceSnapshotEnrichment(state, mutation.messages)
    val merged = enriched.next.timeline.mergeServerMessages(mutation.messages).first
    val next = enriched.next.copy(
        timeline = merged,
        highestRequestedReconcileGeneration = maxOf(state.highestRequestedReconcileGeneration, mutation.generation),
        highestAppliedReconcileGeneration = mutation.generation,
    )
    return changedIfNeeded(state, next)
}

private fun changedIfNeeded(state: TimelineReducerState, next: TimelineReducerState): TimelineReduction = TimelineReduction(
    next,
    result = if (next == state) TimelineReductionResult.NoChange
    else TimelineReductionResult.Changed(TimelineChangeKind.RECONCILED),
)

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

/** A deliberately small semantic model: no production reducer/helper is used to derive expectations. */
internal class LocalMutationParityVerifier(
    private val reducer: (TimelineReducerState, TimelineMutation) -> TimelineReduction = ::reduceProductionMutation,
) {
    fun verify(seed: Long, operations: List<LocalSemanticMutation>) {
        val mismatch = firstMismatch(operations) ?: return
        val minimized = shrink(operations)
        val minimizedMismatch = firstMismatch(minimized) ?: mismatch
        throw AssertionError(
            "timeline mutation parity failure seed=$seed step=${minimizedMismatch.step}; " +
                "expected=${minimizedMismatch.expected.diagnostic()}; actual=${minimizedMismatch.actual.diagnostic()}; " +
                "shrunk=${minimized.joinToString(prefix = "[", postfix = "]") { it.diagnostic() }}",
        )
    }

    private fun firstMismatch(operations: List<LocalSemanticMutation>): ParityMismatch? {
        val owner = TimelineMutationParityOwner(TimelineReducerState(Timeline("parity-generated")), reducer)
        val model = LocalSemanticModel()
        operations.forEachIndexed { index, operation ->
            val mutation = operation.toMutation(index.toLong() + 1L)
            owner.enqueue(mutation)
            val entry = owner.drainOne() ?: error("queued mutation was not drained")
            val expected = model.apply(operation, mutation.sequence)
            val actual = LocalSemanticObservation(owner.currentState().localSnapshot(), entry.orderedEffects, entry.ack)
            if (expected != actual) return ParityMismatch(index, expected, actual)
        }
        return null
    }

    private fun shrink(original: List<LocalSemanticMutation>): List<LocalSemanticMutation> {
        var candidate = original
        var chunk = candidate.size / 2
        while (chunk > 0) {
            var start = 0
            while (start + chunk <= candidate.size) {
                val trial = candidate.take(start) + candidate.drop(start + chunk)
                if (trial.isNotEmpty() && firstMismatch(trial) != null) {
                    candidate = trial
                    start = 0
                } else {
                    start++
                }
            }
            chunk /= 2
        }
        return candidate
    }
}

internal data class AssistantFixture(
    val id: String,
    val content: String,
    val runId: String = "run-$id",
    val otid: String = "otid-$id",
)

internal data class ConfirmedFixture(
    val id: String,
    val content: String,
    val position: Double,
    val stepId: String? = "turn",
)

internal data class PendingReturnFixture(val callId: String, val id: String, val response: String)

internal sealed interface LocalSemanticMutation {
    data class Append(val otid: String, val content: String) : LocalSemanticMutation
    data class Retry(val otid: String) : LocalSemanticMutation
    data class MarkSent(val otid: String) : LocalSemanticMutation
    data class MarkFailed(val otid: String) : LocalSemanticMutation
    data class Reset(val epoch: Long) : LocalSemanticMutation
}

internal object LocalMutationCaseGenerator {
    const val DEFAULT_CASES = 32
    const val DEFAULT_STEPS = 24

    fun generate(seed: Long, steps: Int = DEFAULT_STEPS): List<LocalSemanticMutation> {
        val random = PortableLcg(seed)
        return List(steps) {
            val id = "id-${random.nextInt(4)}"
            when (random.nextInt(5)) {
                0 -> LocalSemanticMutation.Append(id, "value-${random.nextInt(8)}")
                1 -> LocalSemanticMutation.Retry(id)
                2 -> LocalSemanticMutation.MarkSent(id)
                3 -> LocalSemanticMutation.MarkFailed(id)
                else -> LocalSemanticMutation.Reset(random.nextInt(4).toLong())
            }
        }
    }
}

/** Fixed-width arithmetic keeps generated cases identical on every KMP target. */
private class PortableLcg(seed: Long) {
    private var state = seed.toUInt()

    fun nextInt(bound: Int): Int {
        require(bound > 0)
        state = state * 1_664_525u + 1_013_904_223u
        return (state % bound.toUInt()).toInt()
    }
}

private class LocalSemanticModel {
    private val rows = linkedMapOf<String, ModelLocal>()
    private var epoch = 0L

    fun apply(operation: LocalSemanticMutation, sequence: Long): LocalSemanticObservation {
        val effects = when (operation) {
            is LocalSemanticMutation.Append -> append(operation)
            is LocalSemanticMutation.Retry -> retry(operation.otid)
            is LocalSemanticMutation.MarkSent -> update(operation.otid, DeliveryState.SENT)
            is LocalSemanticMutation.MarkFailed -> update(operation.otid, DeliveryState.FAILED)
            is LocalSemanticMutation.Reset -> { epoch = operation.epoch; emptyList() }
        }
        return LocalSemanticObservation(LocalSemanticSnapshot(rows.values.toList(), epoch, sequence), effects, TestAckOutcome.COMPLETED)
    }

    private fun append(operation: LocalSemanticMutation.Append): List<String> {
        if (rows.containsKey(operation.otid)) return emptyList()
        rows[operation.otid] = ModelLocal(operation.otid, operation.content, DeliveryState.SENDING)
        return listOf("send:${operation.otid}:${safeValue(operation.content)}", "event:local-appended:${operation.otid}")
    }

    private fun retry(otid: String): List<String> {
        val row = rows[otid] ?: return emptyList()
        if (row.delivery != DeliveryState.FAILED) return emptyList()
        rows[otid] = row.copy(delivery = DeliveryState.SENDING)
        return listOf("send:$otid:${safeValue(row.content)}")
    }

    private fun update(otid: String, delivery: DeliveryState): List<String> {
        val row = rows[otid] ?: return emptyList()
        rows[otid] = row.copy(delivery = delivery)
        return emptyList()
    }
}

private fun LocalSemanticMutation.toMutation(sequence: Long): TimelineMutation = when (this) {
    is LocalSemanticMutation.Append -> TimelineMutation.LocalAppend(
        sequence,
        PendingSend(otid, content),
        parseTimelineInstant("2026-01-01T00:00:00Z"),
    )
    is LocalSemanticMutation.Retry -> TimelineMutation.RetryLocal(sequence, otid)
    is LocalSemanticMutation.MarkSent -> TimelineMutation.MarkLocalSent(sequence, otid)
    is LocalSemanticMutation.MarkFailed -> TimelineMutation.MarkLocalFailed(sequence, otid)
    is LocalSemanticMutation.Reset -> TimelineMutation.LifecycleReset(sequence, epoch)
}

private data class ModelLocal(val otid: String, val content: String, val delivery: DeliveryState)
private data class LocalSemanticSnapshot(val rows: List<ModelLocal>, val epoch: Long, val sequence: Long)
private data class LocalSemanticObservation(
    val snapshot: LocalSemanticSnapshot,
    val effects: List<String>,
    val ack: TestAckOutcome,
)
private data class ParityMismatch(val step: Int, val expected: LocalSemanticObservation, val actual: LocalSemanticObservation)

private fun LocalSemanticObservation.diagnostic(): String =
    "rows=${snapshot.rows.map { "${it.otid}:${safeValue(it.content)}:${it.delivery}" }}," +
        "epoch=${snapshot.epoch},sequence=${snapshot.sequence},effects=$effects,ack=$ack"

private fun LocalSemanticMutation.diagnostic(): String = when (this) {
    is LocalSemanticMutation.Append -> "Append(otid=$otid,content=${safeValue(content)})"
    is LocalSemanticMutation.Retry -> "Retry(otid=$otid)"
    is LocalSemanticMutation.MarkSent -> "MarkSent(otid=$otid)"
    is LocalSemanticMutation.MarkFailed -> "MarkFailed(otid=$otid)"
    is LocalSemanticMutation.Reset -> "Reset(epoch=$epoch)"
}

private fun TimelineReducerState.localSnapshot() = LocalSemanticSnapshot(
    timeline.events.filterIsInstance<TimelineEvent.Local>().map { ModelLocal(it.otid, it.content, it.deliveryState) },
    lifecycleEpoch,
    lastAppliedMutationSequence,
)

internal fun TimelineReducerState.semanticFingerprint(): String = buildString {
    append("events=[")
    append(timeline.events.joinToString(";") { event ->
        when (event) {
            is TimelineEvent.Local -> "local:${event.otid}:${safeValue(event.content)}:${event.deliveryState}:${event.position}:" +
                "${event.sentAt}:attachments=${event.attachments.size}"
            is TimelineEvent.Confirmed -> "confirmed:${event.serverId}:${event.otid}:${event.messageType}:${safeValue(event.content)}:${event.position}:" +
                "${event.date}:${event.runId}:${event.stepId}:attachments=${event.attachments.size}:" +
                "${event.seqId}:${event.approvalDecided}:${event.approvalDecision}:" +
                "returns=${event.toolReturnContentByCallId.safeEntries()}:" +
                "errors=${event.toolReturnIsErrorByCallId.stableEntries()}:" +
                "truncations=${event.toolReturnTruncationByCallId.stableEntries()}"
        }
    })
    append("];cursors=${timeline.liveCursor},${timeline.backfillCursor}")
    append(";retention=${timeline.releasedOlderCount}:${timeline.stablePrefixVersion}:${timeline.visibleRevision}")
    append(";suppressions=")
    append(timeline.abandonedAssistantFragmentSuppressions
        .sortedWith(compareBy({ it.serverId ?: "" }, { it.runId ?: "" }, { it.contentFingerprint }))
        .joinToString(",") { "${it.serverId ?: "<null>"}:${it.runId ?: "<null>"}:${safeValue(it.contentFingerprint)}" })
    append(";residentOtids=${timeline.residentOtids.sorted().joinToString(",")}")
    append(";residentServerIds=${timeline.events.filterIsInstance<TimelineEvent.Confirmed>().map { it.serverId }.sorted().joinToString(",")}")
    append(";pending=")
    append(pendingToolReturnsByCallId.entries.sortedBy { it.key }.joinToString { (callId, message) ->
        "${callId.lengthPrefixed()}:${message.id.lengthPrefixed()}:${safeValue(message.toolReturn.funcResponse)}"
    })
    append(";generations=$hydrateGeneration,$highestRequestedReconcileGeneration,$highestAppliedReconcileGeneration")
    append(";epoch=$lifecycleEpoch;freshness=$freshnessSequence;sequence=$lastAppliedMutationSequence")
}

private fun Map<String, String>.safeEntries(): String =
    entries.sortedBy { it.key }.joinToString { (key, value) -> "${key.lengthPrefixed()}:${safeValue(value)}" }

private fun Map<*, *>.stableEntries(): String =
    entries.sortedBy { it.key.toString() }.joinToString { entry ->
        listOf(entry.key.toString(), entry.value.toString()).joinToString(prefix = "[", postfix = "]") { it.lengthPrefixed() }
    }

private fun String.lengthPrefixed(): String = "${length}:$this"

private fun effectFingerprint(effect: TimelineReductionEffect): String = when (effect) {
    is TimelineReductionEffect.EmitSyncEvent -> when (val event = effect.event) {
        is TimelineSyncEvent.LocalAppended -> "event:local-appended:${event.otid}"
        else -> "event:$event"
    }
    is TimelineReductionEffect.Notify -> "notification:${effect.notification.serverId}:${effect.notification.messageType}:${safeValue(effect.notification.contentPreview)}"
    is TimelineReductionEffect.Send -> "send:${effect.pending.otid}:${safeValue(effect.pending.content)}"
    is TimelineReductionEffect.PersistPendingLocal -> "persist:${effect.pending.otid}:${effect.sentAt}"
    is TimelineReductionEffect.DeletePendingLocal -> "delete:${effect.otid}"
    is TimelineReductionEffect.AdvanceCursor -> "cursor:${effect.cursor}"
}

private fun safeValue(value: String?): String =
    if (value == null) "null" else "len=${value.length},hash=${value.hashCode().toUInt().toString(16)}"
