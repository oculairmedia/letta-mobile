package com.letta.mobile.data.timeline

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * Synchronizes the processor-owned immutable seed with temporary legacy writers.
 *
 * Both callbacks run under the processor's [Mutex]. Legacy writers must use the
 * same mutex. That makes "read legacy seed -> reduce -> publish both states" one
 * atomic operation without moving stream, hydrate, reconcile, or cleanup logic
 * into this slice.
 */
interface TimelineProcessorStateBridge {
    fun synchronizeSeed(processorState: TimelineReducerState): TimelineReducerState = processorState
    fun publish(state: TimelineReducerState) = Unit
}

object NoOpTimelineProcessorStateBridge : TimelineProcessorStateBridge

/**
 * Serial owner for timeline mutations.
 *
 * A single channel consumer assigns sequence numbers, publishes immutable state
 * before running effects, and completes the typed ack only after ordered effects
 * finish. A failed effect fails only its mutation; the consumer continues with
 * later work. Graceful close drains accepted work, while owner cancellation
 * fails the current and all buffered acknowledgements instead of stranding them.
 */
class TimelineProcessor(
    initialState: TimelineReducerState,
    scope: CoroutineScope,
    private val writeMutex: Mutex = Mutex(),
    private val stateBridge: TimelineProcessorStateBridge = NoOpTimelineProcessorStateBridge,
    private val reducer: (TimelineReducerState, TimelineMutation) -> TimelineReduction = ::reduceProductionMutation,
    private val effectHandler: suspend (TimelineReductionEffect) -> Unit = {},
) {
    private val requests = Channel<ProcessorRequest>(Channel.UNLIMITED)
    private val accepting = atomic(true)
    private val terminalReason = atomic<TerminalReason?>(null)
    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<TimelineReducerState> = _state.asStateFlow()
    private val consumer: Job = scope.launch { consume(initialState.lastAppliedMutationSequence + 1L) }

    /** Enqueue without tying processor progress to the caller's cancellation. */
    fun enqueue(mutation: TimelineMutation): Deferred<TimelineProcessorAck> {
        val ack = CompletableDeferred<TimelineProcessorAck>()
        if (!accepting.value) {
            ack.complete(terminalAck())
            return ack
        }
        val sent = requests.trySend(ProcessorRequest(mutation, ack))
        if (sent.isFailure) ack.complete(terminalAck())
        return ack
    }

    suspend fun submit(mutation: TimelineMutation): TimelineProcessorAck = enqueue(mutation).await()

    /** Stop accepting new work and drain every request already accepted. */
    fun close() {
        if (accepting.compareAndSet(expect = true, update = false)) {
            terminalReason.compareAndSet(expect = null, update = TerminalReason.CLOSED)
            requests.close()
        }
    }

    suspend fun closeAndJoin() {
        close()
        consumer.join()
    }

    private suspend fun consume(initialNextSequence: Long) {
        var nextSequence = initialNextSequence
        var active: ProcessorRequest? = null
        try {
            for (request in requests) {
                active = request
                val sequence = nextSequence++
                process(request, sequence)
                active = null
            }
        } catch (cancelled: CancellationException) {
            terminalReason.value = TerminalReason.CANCELLED
            active?.ack?.complete(
                TimelineProcessorAck.Failed(
                    sequence = null,
                    reason = TimelineProcessorFailureReason.Cancelled,
                ),
            )
            throw cancelled
        } finally {
            accepting.value = false
            if (terminalReason.value == null) terminalReason.value = TerminalReason.CANCELLED
            requests.close()
            while (true) {
                val request = requests.tryReceive().getOrNull() ?: break
                request.ack.complete(terminalAck())
            }
        }
    }

    private suspend fun process(request: ProcessorRequest, sequence: Long) {
        var reduction: TimelineReduction? = null
        var rejection: TimelineProcessorRejectionReason? = null
        try {
            writeMutex.withLock {
                val synchronized = stateBridge.synchronizeSeed(_state.value)
                rejection = rejectionReason(sequence, request.mutation, synchronized)
                if (rejection == null) {
                    val reduced = reducer(synchronized, request.mutation)
                    val committed = reduced.copy(
                        next = reduced.next.copy(lastAppliedMutationSequence = sequence),
                    )
                    _state.value = committed.next
                    stateBridge.publish(committed.next)
                    reduction = committed
                } else if (_state.value != synchronized) {
                    _state.value = synchronized
                }
            }
        } catch (cancelled: CancellationException) {
            request.ack.complete(
                TimelineProcessorAck.Failed(sequence, TimelineProcessorFailureReason.Cancelled),
            )
            throw cancelled
        } catch (failure: Throwable) {
            request.ack.complete(
                TimelineProcessorAck.Failed(
                    sequence,
                    TimelineProcessorFailureReason.StatePublicationFailure(failure),
                ),
            )
            return
        }

        rejection?.let { reason ->
            request.ack.complete(TimelineProcessorAck.Rejected(sequence, reason))
            return
        }

        val committed = checkNotNull(reduction)
        committed.effects.forEachIndexed { index, effect ->
            try {
                effectHandler(effect)
            } catch (cancelled: CancellationException) {
                request.ack.complete(
                    TimelineProcessorAck.Failed(sequence, TimelineProcessorFailureReason.Cancelled),
                )
                throw cancelled
            } catch (failure: Throwable) {
                request.ack.complete(
                    TimelineProcessorAck.Failed(
                        sequence,
                        TimelineProcessorFailureReason.EffectFailure(index, effect, failure),
                    ),
                )
                return
            }
        }
        request.ack.complete(TimelineProcessorAck.Applied(sequence, committed.result))
    }

    private fun terminalAck(): TimelineProcessorAck = when (terminalReason.value) {
        TerminalReason.CANCELLED -> TimelineProcessorAck.Failed(
            sequence = null,
            reason = TimelineProcessorFailureReason.Cancelled,
        )
        TerminalReason.CLOSED, null -> TimelineProcessorAck.Rejected(
            sequence = null,
            reason = TimelineProcessorRejectionReason.Closed,
        )
    }

    private enum class TerminalReason { CLOSED, CANCELLED }

    private data class ProcessorRequest(
        val mutation: TimelineMutation,
        val ack: CompletableDeferred<TimelineProcessorAck>,
    )
}

private fun rejectionReason(
    sequence: Long,
    mutation: TimelineMutation,
    state: TimelineReducerState,
): TimelineProcessorRejectionReason? = when {
    sequence <= state.lastAppliedMutationSequence -> TimelineProcessorRejectionReason.StaleSequence(
        attempted = sequence,
        lastApplied = state.lastAppliedMutationSequence,
    )
    mutation is TimelineMutation.HydrateSnapshot && mutation.generation < state.hydrateGeneration ->
        TimelineProcessorRejectionReason.StaleGeneration(
            mutationFamily = "HydrateSnapshot",
            attempted = mutation.generation,
            current = state.hydrateGeneration,
        )
    mutation is TimelineMutation.ReconcileSnapshot &&
        mutation.generation < state.highestRequestedReconcileGeneration ->
        TimelineProcessorRejectionReason.StaleGeneration(
            mutationFamily = "ReconcileSnapshot",
            attempted = mutation.generation,
            current = state.highestRequestedReconcileGeneration,
        )
    else -> null
}

internal fun TimelineProcessorAck.appliedResultOrThrow(): TimelineReductionResult = when (this) {
    is TimelineProcessorAck.Applied -> result
    is TimelineProcessorAck.Rejected -> throw TimelineProcessorMutationException("timeline mutation rejected: $reason")
    is TimelineProcessorAck.Failed -> throw TimelineProcessorMutationException("timeline mutation failed: $reason")
}

internal class TimelineProcessorMutationException(message: String) : IllegalStateException(message)
