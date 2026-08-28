package com.letta.mobile.data.timeline

import kotlin.jvm.JvmInline
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Result of one mutation submitted to [TimelineProcessor]. */
sealed interface TimelineProcessorAck {
    val sequence: Long?

    data class Applied(
        override val sequence: Long,
        val result: TimelineReductionResult,
    ) : TimelineProcessorAck

    data class Rejected(
        override val sequence: Long?,
        val reason: TimelineProcessorRejectionReason,
    ) : TimelineProcessorAck

    data class Failed(
        override val sequence: Long?,
        val reason: TimelineProcessorFailureReason,
    ) : TimelineProcessorAck
}

sealed interface TimelineProcessorRejectionReason {
    data class StaleSequence(val attempted: Long, val lastApplied: Long) : TimelineProcessorRejectionReason

    data class StaleGeneration(
        val mutationFamily: String,
        val attempted: Long,
        val current: Long,
    ) : TimelineProcessorRejectionReason

    /** The bounded mailbox was full. The mutation was not accepted or sequenced. */
    data class MailboxFull(val capacity: Int) : TimelineProcessorRejectionReason

    data object Closed : TimelineProcessorRejectionReason
}

sealed interface TimelineProcessorFailureReason {
    data object Cancelled : TimelineProcessorFailureReason

    data class EffectFailure(
        val effectIndex: Int,
        val effect: TimelineReductionEffect,
        val cause: Throwable,
    ) : TimelineProcessorFailureReason

    data class StatePublicationFailure(val cause: Throwable) : TimelineProcessorFailureReason
}

/**
 * Serial owner for timeline mutations.
 *
 * A single channel consumer assigns sequence numbers in accepted mailbox order,
 * commits immutable state atomically, then runs effects in reducer order. The
 * acknowledgement is terminal: [TimelineProcessorAck.Applied] means every effect
 * completed, while [TimelineProcessorAck.Failed] means state committed but the
 * named effect did not complete. A failed effect is isolated to its mutation and
 * later mutations continue.
 *
 * The mailbox is bounded and producers never suspend while enqueueing. When it
 * is full, [TimelineProcessorRejectionReason.MailboxFull] is returned immediately;
 * callers may retry explicitly. [close] atomically stops admission and drains all
 * accepted requests. Cancellation is preemptive and fails the active and buffered
 * acknowledgements, so no caller is left waiting on a stranded deferred.
 */
@JvmInline
private value class TimelineSequence(val value: Long) {
    fun next() = TimelineSequence(value + 1L)
}

@JvmInline
private value class TimelineEffectIndex(val value: Int)

class TimelineProcessor(
    initialState: TimelineReducerState,
    scope: CoroutineScope,
    private val reducer: (TimelineReducerState, TimelineMutation) -> TimelineReduction = ::reduceProductionMutation,
    private val effectHandler: suspend (TimelineReductionEffect) -> Unit = {},
    mailboxCapacity: Int = DEFAULT_MAILBOX_CAPACITY,
) {
    private val capacity = mailboxCapacity.also { require(it > 0) { "mailboxCapacity must be positive" } }
    private val requests = Channel<ProcessorRequest>(capacity)
    private val accepting = atomic(true)
    private val terminalReason = atomic<TerminalReason?>(null)
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<TimelineReducerState> = _state.asStateFlow()

    /** Canonical timeline projection backed directly by [state], without a second publication. */
    val timeline: StateFlow<Timeline> = ProcessorTimelineStateFlow(state)
    private val consumer: Job = scope.launch {
        consume(TimelineSequence(initialState.lastAppliedMutationSequence + 1L))
    }

    /** Enqueue without tying processor progress to caller cancellation or blocking a producer. */
    fun enqueue(mutation: TimelineMutation): Deferred<TimelineProcessorAck> {
        val ack = CompletableDeferred<TimelineProcessorAck>()
        if (!accepting.value) return ack.completedWith(terminalAck())

        val sent = requests.trySend(ProcessorRequest(mutation, ack))
        if (sent.isFailure) {
            val rejected = if (sent.isClosed || !accepting.value) {
                terminalAck()
            } else {
                TimelineProcessorAck.Rejected(
                    sequence = null,
                    reason = TimelineProcessorRejectionReason.MailboxFull(capacity),
                )
            }
            ack.complete(rejected)
        }
        return ack
    }

    suspend fun submit(mutation: TimelineMutation): TimelineProcessorAck = enqueue(mutation).await()

    suspend fun submitWithBackpressure(mutation: TimelineMutation): TimelineProcessorAck {
        val acknowledgement = CompletableDeferred<TimelineProcessorAck>()
        if (!accepting.value) return terminalAck()
        try {
            requests.send(ProcessorRequest(mutation, acknowledgement))
        } catch (_: ClosedSendChannelException) {
            return terminalAck()
        }
        return acknowledgement.await()
    }

    /** Stop accepting new work and drain every request already accepted. */
    fun close() {
        if (accepting.compareAndSet(expect = true, update = false)) {
            terminalReason.compareAndSet(expect = null, update = TerminalReason.CLOSED)
            requests.close()
        }
    }

    /** Gracefully close admission and wait until all accepted work has a terminal acknowledgement. */
    suspend fun closeAndJoin() {
        close()
        consumer.join()
    }

    private suspend fun consume(initialNextSequence: TimelineSequence) {
        var nextSequence = initialNextSequence
        var active: ProcessorRequest? = null
        try {
            for (request in requests) {
                active = request
                process(request, nextSequence)
                nextSequence = nextSequence.next()
                active = null
            }
        } catch (cancelled: CancellationException) {
            terminalReason.value = TerminalReason.CANCELLED
            active?.ack?.complete(cancelledAck())
            throw cancelled
        } finally {
            accepting.value = false
            if (terminalReason.value == null) terminalReason.value = TerminalReason.CANCELLED
            requests.close()
            drainBufferedAcks()
        }
    }

    private suspend fun process(request: ProcessorRequest, sequence: TimelineSequence) {
        val prepared = try {
            prepareMutation(request.mutation, sequence)
        } catch (cancelled: CancellationException) {
            request.ack.complete(cancelledAck(sequence))
            throw cancelled
        } catch (failure: Throwable) {
            request.ack.complete(
                TimelineProcessorAck.Failed(
                    sequence.value,
                    TimelineProcessorFailureReason.StatePublicationFailure(failure),
                ),
            )
            return
        }

        when (prepared) {
            is PreparedMutation.Rejected -> request.ack.complete(
                TimelineProcessorAck.Rejected(sequence.value, prepared.reason),
            )
            is PreparedMutation.Committed -> executeEffects(request, sequence, prepared.reduction)
        }
    }

    private fun prepareMutation(
        mutation: TimelineMutation,
        sequence: TimelineSequence,
    ): PreparedMutation {
        val current = _state.value
        val rejection = rejectionReason(sequence, mutation, current)
        if (rejection != null) return PreparedMutation.Rejected(rejection)

        val reduced = reducer(current, mutation)
        val committed = reduced.copy(
            next = reduced.next.copy(lastAppliedMutationSequence = sequence.value),
        )
        _state.value = committed.next
        return PreparedMutation.Committed(committed)
    }

    private suspend fun executeEffects(
        request: ProcessorRequest,
        sequence: TimelineSequence,
        committed: TimelineReduction,
    ) {
        committed.effects.forEachIndexed { index, effect ->
            val failure = runEffect(effect, sequence, TimelineEffectIndex(index), request) ?: return@forEachIndexed
            request.ack.complete(failure)
            return
        }
        request.ack.complete(TimelineProcessorAck.Applied(sequence.value, committed.result))
    }

    private suspend fun runEffect(
        effect: TimelineReductionEffect,
        sequence: TimelineSequence,
        index: TimelineEffectIndex,
        request: ProcessorRequest,
    ): TimelineProcessorAck.Failed? = try {
        effectHandler(effect)
        null
    } catch (cancelled: CancellationException) {
        request.ack.complete(cancelledAck(sequence))
        throw cancelled
    } catch (failure: Throwable) {
        TimelineProcessorAck.Failed(
            sequence.value,
            TimelineProcessorFailureReason.EffectFailure(index.value, effect, failure),
        )
    }

    private fun drainBufferedAcks() {
        while (true) {
            val request = requests.tryReceive().getOrNull() ?: return
            request.ack.complete(terminalAck())
        }
    }

    private fun terminalAck(): TimelineProcessorAck = when (terminalReason.value) {
        TerminalReason.CANCELLED -> cancelledAck()
        TerminalReason.CLOSED, null -> TimelineProcessorAck.Rejected(
            sequence = null,
            reason = TimelineProcessorRejectionReason.Closed,
        )
    }

    private fun cancelledAck(sequence: TimelineSequence? = null) = TimelineProcessorAck.Failed(
        sequence = sequence?.value,
        reason = TimelineProcessorFailureReason.Cancelled,
    )

    private fun CompletableDeferred<TimelineProcessorAck>.completedWith(
        acknowledgement: TimelineProcessorAck,
    ): CompletableDeferred<TimelineProcessorAck> = apply { complete(acknowledgement) }

    private sealed interface PreparedMutation {
        data class Rejected(val reason: TimelineProcessorRejectionReason) : PreparedMutation
        data class Committed(val reduction: TimelineReduction) : PreparedMutation
    }

    private enum class TerminalReason { CLOSED, CANCELLED }

    private data class ProcessorRequest(
        val mutation: TimelineMutation,
        val ack: CompletableDeferred<TimelineProcessorAck>,
    )

    companion object {
        const val DEFAULT_MAILBOX_CAPACITY = 64
    }
}

/**
 * Read-only timeline view of processor state with no independent cache, queue, or coroutine.
 * [value] therefore observes the same immutable commit that processor acknowledgements and
 * effects observe, while collectors receive the processor's publication order unchanged.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
private class ProcessorTimelineStateFlow(
    private val processorState: StateFlow<TimelineReducerState>,
) : StateFlow<Timeline> {
    override val value: Timeline
        get() = processorState.value.timeline

    override val replayCache: List<Timeline>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<Timeline>): Nothing =
        processorState.collect { state -> collector.emit(state.timeline) }
}

private fun rejectionReason(
    sequence: TimelineSequence,
    mutation: TimelineMutation,
    state: TimelineReducerState,
): TimelineProcessorRejectionReason? = when {
    sequence.value <= state.lastAppliedMutationSequence -> TimelineProcessorRejectionReason.StaleSequence(
        attempted = sequence.value,
        lastApplied = state.lastAppliedMutationSequence,
    )
    mutation is TimelineMutation.HydrateSnapshot && mutation.generation < state.hydrateGeneration ->
        staleGeneration(GenerationWindow("HydrateSnapshot", mutation.generation, state.hydrateGeneration))
    mutation is TimelineMutation.ReconcileSnapshot &&
        mutation.generation < state.highestRequestedReconcileGeneration ->
        staleGeneration(
            GenerationWindow(
                "ReconcileSnapshot",
                mutation.generation,
                state.highestRequestedReconcileGeneration,
            ),
        )
    mutation is TimelineMutation.RecentMessagesSnapshot &&
        mutation.generation < state.highestAppliedReconcileGeneration ->
        staleGeneration(
            GenerationWindow(
                "RecentMessagesSnapshot.generation",
                mutation.generation,
                state.highestAppliedReconcileGeneration,
            ),
        )
    mutation is TimelineMutation.RecentMessagesSnapshot &&
        mutation.freshnessSequence < state.freshnessSequence ->
        staleGeneration(
            GenerationWindow(
                "RecentMessagesSnapshot.freshness",
                mutation.freshnessSequence,
                state.freshnessSequence,
            ),
        )
    else -> null
}

private data class GenerationWindow(
    val mutationFamily: String,
    val attempted: Long,
    val current: Long,
)

private fun staleGeneration(window: GenerationWindow) = TimelineProcessorRejectionReason.StaleGeneration(
    window.mutationFamily,
    window.attempted,
    window.current,
)

internal suspend fun TimelineProcessor.submitMaintenanceMutation(
    mutation: TimelineMutation,
    maxAttempts: Int = 2,
): TimelineProcessorAck {
    require(maxAttempts > 0) { "maxAttempts must be positive" }
    repeat(maxAttempts - 1) {
        val acknowledgement = submitWithBackpressure(mutation)
        val failed = acknowledgement as? TimelineProcessorAck.Failed ?: return acknowledgement
        if (failed.reason !is TimelineProcessorFailureReason.StatePublicationFailure) return failed
    }
    return submitWithBackpressure(mutation)
}

internal fun TimelineProcessorAck.appliedResultOrThrow(): TimelineReductionResult = when (this) {
    is TimelineProcessorAck.Applied -> result
    is TimelineProcessorAck.Rejected -> throw TimelineProcessorMutationException("timeline mutation rejected: $reason")
    is TimelineProcessorAck.Failed -> throw TimelineProcessorMutationException("timeline mutation failed: $reason")
}

internal class TimelineProcessorMutationException(message: String) : IllegalStateException(message)
