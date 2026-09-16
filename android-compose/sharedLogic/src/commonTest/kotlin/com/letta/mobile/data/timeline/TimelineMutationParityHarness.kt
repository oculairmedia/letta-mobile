package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.MessageContentPart
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope

/** Test adapter whose mutation execution is the production [TimelineProcessor]. */
internal class TimelineMutationParityOwner(
    initial: TimelineReducerState,
    scope: CoroutineScope,
    reducer: (TimelineReducerState, TimelineMutation) -> TimelineReduction = ::reduceProductionMutation,
) {
    private val queued = ArrayDeque<QueuedMutation>()
    private val appliedEffects = mutableListOf<String>()
    private var closed = false
    private val processor = TimelineProcessor(
        initialState = initial,
        scope = scope,
        reducer = reducer,
        effectHandler = { effect -> appliedEffects += effect.semanticFingerprint() },
    )
    val journal = mutableListOf<TimelineMutationJournalEntry>()

    fun enqueue(mutation: TimelineMutation): TestMutationAck {
        val ack = TestMutationAck()
        if (closed) {
            ack.reject(TimelineProcessorRejectionReason.Closed)
        } else {
            queued.addLast(QueuedMutation(mutation, ack))
        }
        return ack
    }

    suspend fun drainOne(): TimelineMutationJournalEntry? {
        val queuedMutation = queued.removeFirstOrNull() ?: return null
        appliedEffects.clear()
        val processorAck = processor.submit(queuedMutation.mutation)
        queuedMutation.ack.completeFrom(processorAck)
        return TimelineMutationJournalEntry(
            attemptedSequence = processorAck.sequence,
            acceptedSequence = (processorAck as? TimelineProcessorAck.Applied)?.sequence,
            mutationFamily = queuedMutation.mutation::class.simpleName ?: "unknown",
            state = processor.state.value.semanticFingerprint(),
            orderedEffects = appliedEffects.toList(),
            result = (processorAck as? TimelineProcessorAck.Applied)?.result,
            ack = queuedMutation.ack.outcome,
            ackReason = queuedMutation.ack.reason,
        ).also(journal::add)
    }

    suspend fun drain() {
        while (queued.isNotEmpty()) drainOne()
    }

    fun close(reason: TestCloseReason = TestCloseReason.OWNER_CLOSED) {
        closed = true
        processor.close()
        while (queued.isNotEmpty()) {
            val ack = queued.removeFirst().ack
            when (reason) {
                TestCloseReason.OWNER_CLOSED -> ack.reject(TimelineProcessorRejectionReason.Closed)
                TestCloseReason.CANCELLED -> ack.fail(TimelineProcessorFailureReason.Cancelled)
            }
        }
    }

    fun currentState(): TimelineReducerState = processor.state.value
}

internal data class TimelineMutationJournalEntry(
    val attemptedSequence: Long?,
    val acceptedSequence: Long?,
    val mutationFamily: String,
    val state: String,
    val orderedEffects: List<String>,
    val result: TimelineReductionResult?,
    val ack: TestAckOutcome,
    val ackReason: TimelineJournalAckReason?,
) {
    fun trace(): List<String> = buildList {
        add("state:$state")
        orderedEffects.forEach(::add)
        add("ack:$ack${ackReason?.let { ":${it.redactedDiagnostic()}" }.orEmpty()}")
    }
}

internal enum class TestAckOutcome { PENDING, COMPLETED, REJECTED, FAILED }
internal enum class TestCloseReason { OWNER_CLOSED, CANCELLED }

internal sealed interface TimelineJournalAckReason {
    data class Rejection(val reason: TimelineProcessorRejectionReason) : TimelineJournalAckReason
    data class Failure(val reason: TimelineProcessorFailureReason) : TimelineJournalAckReason
}

internal class TestMutationAck {
    var outcome: TestAckOutcome = TestAckOutcome.PENDING
        private set
    var reason: TimelineJournalAckReason? = null
        private set

    fun reject(value: TimelineProcessorRejectionReason) {
        outcome = TestAckOutcome.REJECTED
        reason = TimelineJournalAckReason.Rejection(value)
    }

    fun fail(value: TimelineProcessorFailureReason) {
        outcome = TestAckOutcome.FAILED
        reason = TimelineJournalAckReason.Failure(value)
    }

    fun completeFrom(ack: TimelineProcessorAck) {
        when (ack) {
            is TimelineProcessorAck.Applied -> outcome = TestAckOutcome.COMPLETED
            is TimelineProcessorAck.Rejected -> reject(ack.reason)
            is TimelineProcessorAck.Failed -> fail(ack.reason)
        }
    }
}

private fun TimelineJournalAckReason.redactedDiagnostic(): String = when (this) {
    is TimelineJournalAckReason.Rejection -> reason.toString()
    is TimelineJournalAckReason.Failure -> when (val value = reason) {
        TimelineProcessorFailureReason.Cancelled -> "Cancelled"
        is TimelineProcessorFailureReason.EffectFailure ->
            "EffectFailure(index=${value.effectIndex},effect=${value.effect::class.simpleName},cause=${value.cause::class.simpleName})"
        is TimelineProcessorFailureReason.StatePublicationFailure ->
            "StatePublicationFailure(cause=${value.cause::class.simpleName})"
    }
}

private data class QueuedMutation(val mutation: TimelineMutation, val ack: TestMutationAck)

/** Independent small semantic model; only the SUT side invokes production reducers. */
internal class LocalMutationParityVerifier(
    private val reducer: (TimelineReducerState, TimelineMutation) -> TimelineReduction = ::reduceProductionMutation,
) {
    suspend fun verify(seed: Long, operations: List<LocalSemanticMutation>) {
        val mismatch = firstMismatch(operations) ?: return
        val minimized = shrink(operations)
        val minimizedMismatch = firstMismatch(minimized) ?: mismatch
        throw AssertionError(
            "timeline mutation parity failure seed=$seed step=${minimizedMismatch.step}; " +
                "expected=${minimizedMismatch.expected.diagnostic()}; actual=${minimizedMismatch.actual.diagnostic()}; " +
                "shrunk=${minimized.joinToString(prefix = "[", postfix = "]") { it.diagnostic() }}",
        )
    }

    private suspend fun firstMismatch(operations: List<LocalSemanticMutation>): ParityMismatch? =
        kotlinx.coroutines.coroutineScope {
            val owner = TimelineMutationParityOwner(
                TimelineReducerState(Timeline("parity-generated")),
                this,
                reducer,
            )
            val model = LocalSemanticModel()
            operations.forEachIndexed { index, operation ->
                owner.enqueue(operation.toMutation())
                val entry = owner.drainOne() ?: error("queued mutation was not drained")
                val sequence = requireNotNull(entry.acceptedSequence)
                val expected = model.apply(operation, sequence)
                val actual = LocalSemanticObservation(
                    owner.currentState().localSnapshot(),
                    entry.orderedEffects,
                    entry.ack,
                    entry.result?.changed,
                )
                if (expected != actual) {
                    owner.close()
                    return@coroutineScope ParityMismatch(index, expected, actual)
                }
            }
            owner.close()
            null
        }

    private suspend fun shrink(original: List<LocalSemanticMutation>): List<LocalSemanticMutation> {
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

internal sealed interface LocalSemanticMutation {
    data class Append(
        val otid: String,
        val content: String,
        val attachments: PersistentList<MessageContentPart.Image> = persistentListOf(),
    ) : LocalSemanticMutation
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
internal class PortableLcg(seed: Long) {
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
        val transition = when (operation) {
            is LocalSemanticMutation.Append -> append(operation)
            is LocalSemanticMutation.Retry -> retry(operation.otid)
            is LocalSemanticMutation.MarkSent -> update(operation.otid, DeliveryState.SENT)
            is LocalSemanticMutation.MarkFailed -> update(operation.otid, DeliveryState.FAILED)
            is LocalSemanticMutation.Reset -> {
                val changed = epoch != operation.epoch
                epoch = operation.epoch
                ModelTransition(emptyList(), changed)
            }
        }
        return LocalSemanticObservation(
            LocalSemanticSnapshot(rows.values.toList(), epoch, sequence),
            transition.effects,
            TestAckOutcome.COMPLETED,
            transition.changed,
        )
    }

    private fun append(operation: LocalSemanticMutation.Append): ModelTransition {
        if (rows.containsKey(operation.otid)) return ModelTransition(emptyList(), false)
        rows[operation.otid] = ModelLocal(
            operation.otid,
            operation.content,
            DeliveryState.SENDING,
            operation.attachments,
        )
        val pending = PendingSend(operation.otid, operation.content, operation.attachments)
        return ModelTransition(
            effects = listOf(
                TimelineReductionEffect.Send(pending).semanticFingerprint(),
                TimelineReductionEffect.EmitSyncEvent(
                    TimelineSyncEvent.LocalAppended(operation.otid),
                ).semanticFingerprint(),
            ),
            changed = true,
        )
    }

    private fun retry(otid: String): ModelTransition {
        val row = rows[otid] ?: return ModelTransition(emptyList(), false)
        if (row.delivery != DeliveryState.FAILED) return ModelTransition(emptyList(), false)
        rows[otid] = row.copy(delivery = DeliveryState.SENDING)
        return ModelTransition(
            effects = listOf(
                TimelineReductionEffect.Send(
                    PendingSend(otid, row.content, row.attachments),
                ).semanticFingerprint(),
            ),
            changed = true,
        )
    }

    private fun update(otid: String, delivery: DeliveryState): ModelTransition {
        val row = rows[otid] ?: return ModelTransition(emptyList(), false)
        rows[otid] = row.copy(delivery = delivery)
        return ModelTransition(emptyList(), true)
    }
}

private data class ModelTransition(val effects: List<String>, val changed: Boolean)

private fun LocalSemanticMutation.toMutation(): TimelineMutation = when (this) {
    is LocalSemanticMutation.Append -> TimelineMutation.LocalAppend(
        PendingSend(otid, content, attachments),
        parseTimelineInstant("2026-01-01T00:00:00Z"),
    )
    is LocalSemanticMutation.Retry -> TimelineMutation.RetryLocal(otid)
    is LocalSemanticMutation.MarkSent -> TimelineMutation.MarkLocalSent(otid)
    is LocalSemanticMutation.MarkFailed -> TimelineMutation.MarkLocalFailed(otid)
    is LocalSemanticMutation.Reset -> TimelineMutation.LifecycleReset(epoch)
}

private data class ModelLocal(
    val otid: String,
    val content: String,
    val delivery: DeliveryState,
    val attachments: PersistentList<MessageContentPart.Image>,
)
private data class LocalSemanticSnapshot(val rows: List<ModelLocal>, val epoch: Long, val sequence: Long)
private data class LocalSemanticObservation(
    val snapshot: LocalSemanticSnapshot,
    val effects: List<String>,
    val ack: TestAckOutcome,
    val resultChanged: Boolean?,
)
private data class ParityMismatch(
    val step: Int,
    val expected: LocalSemanticObservation,
    val actual: LocalSemanticObservation,
)

private fun LocalSemanticObservation.diagnostic(): String =
    "rows=${snapshot.rows.map { "${it.otid}:${safeDiagnostic(it.content)}:${it.delivery}:attachments=${it.attachments.size}" }}," +
        "epoch=${snapshot.epoch},sequence=${snapshot.sequence}," +
        "effects=${effects.map(::redactSemanticFingerprint)},ack=$ack,resultChanged=$resultChanged"

private fun LocalSemanticMutation.diagnostic(): String = when (this) {
    is LocalSemanticMutation.Append ->
        "Append(otid=$otid,content=${safeDiagnostic(content)},attachments=${attachments.size})"
    is LocalSemanticMutation.Retry -> "Retry(otid=$otid)"
    is LocalSemanticMutation.MarkSent -> "MarkSent(otid=$otid)"
    is LocalSemanticMutation.MarkFailed -> "MarkFailed(otid=$otid)"
    is LocalSemanticMutation.Reset -> "Reset(epoch=$epoch)"
}

private fun TimelineReducerState.localSnapshot() = LocalSemanticSnapshot(
    timeline.events.filterIsInstance<TimelineEvent.Local>().map {
        ModelLocal(it.otid, it.content, it.deliveryState, it.attachments)
    },
    lifecycleEpoch,
    lastAppliedMutationSequence,
)

internal fun safeDiagnostic(value: String?): String =
    if (value == null) "null" else "len=${value.length},hash=${value.hashCode().toUInt().toString(16)}"

internal fun redactSemanticFingerprint(value: String): String = safeDiagnostic(value)
