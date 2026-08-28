package com.letta.mobile.data.timeline

import com.letta.mobile.util.Telemetry
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

import kotlin.time.Duration.Companion.milliseconds
/**
 * letta-mobile-dangling-tool: post-turn dangling tool-call resolver.
 *
 * PR #900 removed premature "settle dangling tool calls as Failed" behavior
 * from CLEAN turn completions: with async/parallel tools a real return can
 * legitimately arrive after the turn's terminal frame, so guessing at
 * completion time produced a red->green flicker (or worse, a permanently
 * wrong red card while a real success streamed in later). The tradeoff is
 * that if the real return NEVER arrives (server silently drops an async
 * tool, or the stream misses it), the card now spins forever instead of
 * resolving.
 *
 * This class restores a terminal outcome WITHOUT reintroducing a guess.
 * The client never decides a call's fate itself — only the canonical
 * record (server transcript, fetched via [reconcile] -> the existing
 * `message.list` snapshot machinery) can resolve or fail a card. Timers
 * here only decide WHEN to ask the canonical record again; they never
 * decide WHAT the answer is. Only after every attempt in the backoff
 * schedule still shows no return does this settle the card as an honest,
 * non-committal failure ("No tool result recorded" — never "interrupted",
 * since nothing here actually interrupted anything).
 */
class DanglingToolCallResolver(
    private val conversationId: String,
    private val processor: TimelineProcessor,
    private val state: StateFlow<Timeline>,
    private val scope: CoroutineScope,
    private val reconcile: suspend (reason: String, forceRefresh: Boolean) -> Int,
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
) {
    @Volatile
    private var sweepJob: Job? = null

    @Volatile
    private var sweepGeneration: Long = 0L

    /**
     * Cancel any pending sweep. Called when a new turn starts on this
     * conversation — a fresh turn supersedes whatever the previous turn
     * left dangling (the new turn's own end-of-turn handling will schedule
     * its own sweep if needed via [scheduleSweepIfUnresolved]).
     */
    suspend fun cancelPendingSweep() {
        sweepGeneration += 1L
        sweepJob?.cancel()
        sweepJob = null
        processor.submitWithBackpressure(TimelineMutation.AdvanceDanglingSweep(sweepGeneration)).appliedResultOrThrow()
    }

    /**
     * Called whenever a turn ends, on EVERY completion path — clean or
     * abnormal (cancel/timeout/error). If unresolved tool-call cards
     * remain, launches a single bounded background sweep that re-asks the
     * canonical record at [backoffMs] intervals, stopping as soon as every
     * previously-unresolved call id has a return attached.
     *
     * This is intentionally unconditional on [clean] (kept only for
     * telemetry) rather than gated to clean completions. The reason it's
     * safe: the sweep is entirely canonical-record-driven and never
     * guesses — an abnormal turn's OWN tool calls are already settled
     * synchronously by AppServerTurnEngine before this fires, so they never
     * show up in [Timeline.unresolvedToolCallIds]. What this guards against
     * is a DIFFERENT, earlier turn's still-dangling card: `turnStarted()`
     * calls [cancelPendingSweep] for every new turn (including one that
     * later ends abnormally), so without rescheduling here on every
     * `turnEnded`, that earlier card's sweep would be silently dropped with
     * nothing left to ever reschedule it.
     *
     * No-op if nothing is unresolved. Exactly one sweep is active per
     * resolver instance (per conversation) at a time — calling this again
     * (or [cancelPendingSweep]) supersedes any job already in flight.
     */
    suspend fun scheduleSweepIfUnresolved(clean: Boolean = true) {
        cancelPendingSweep()
        if (state.value.unresolvedToolCallIds().isEmpty()) return
        val generation = sweepGeneration
        val lifecycleEpoch = processor.state.value.lifecycleEpoch
        Telemetry.event(
            "TimelineSync", "danglingToolResolve.sweepScheduled",
            "conversationId" to conversationId,
            "clean" to clean,
        )
        sweepJob = scope.launch {
            for ((index, stepMs) in backoffMs.withIndex()) {
                delay(stepMs.milliseconds)
                if (state.value.unresolvedToolCallIds().isEmpty()) {
                    Telemetry.event(
                        "TimelineSync", "danglingToolResolve.resolved",
                        "conversationId" to conversationId,
                        "attempt" to (index + 1),
                    )
                    return@launch
                }
                runCatching { reconcile(REASON, true) }
                    .onFailure { t ->
                        Telemetry.error(
                            "TimelineSync", "danglingToolResolve.reconcileFailed", t,
                            "conversationId" to conversationId,
                            "attempt" to (index + 1),
                        )
                    }
                if (state.value.unresolvedToolCallIds().isEmpty()) {
                    Telemetry.event(
                        "TimelineSync", "danglingToolResolve.resolved",
                        "conversationId" to conversationId,
                        "attempt" to (index + 1),
                    )
                    return@launch
                }
            }
            val exhausted = state.value.unresolvedToolCallIds()
            if (exhausted.isNotEmpty()) {
                Telemetry.event(
                    "TimelineSync", "danglingToolResolve.exhausted",
                    "conversationId" to conversationId,
                    "count" to exhausted.size,
                )
                settleAsFailed(generation, lifecycleEpoch, exhausted)
            }
        }
    }

    /**
     * Hydration guard: an immediate reconcile pass fired when a
     * freshly-hydrated timeline still has unresolved tool-call cards and no
     * turn is currently active for this conversation. Heals stale spinners
     * that survived an app restart or a dropped stream.
     *
     * That single pass is only the first ask, not the last: if the
     * canonical record STILL shows no return afterward, this escalates to
     * the same bounded backoff sweep used for live turns
     * ([scheduleSweepIfUnresolved]) so a restart-survivor card always
     * reaches a terminal outcome (resolved, or honestly settled "No tool
     * result recorded") instead of spinning until some future turn happens
     * to trigger a sweep.
     */
    suspend fun runHydrationGuardIfIdle(turnActive: Boolean) {
        if (turnActive) return
        if (state.value.unresolvedToolCallIds().isEmpty()) return
        Telemetry.event(
            "TimelineSync", "danglingToolResolve.hydrationGuard",
            "conversationId" to conversationId,
        )
        runCatching { reconcile(HYDRATION_REASON, true) }
            .onFailure { t ->
                Telemetry.error(
                    "TimelineSync", "danglingToolResolve.hydrationReconcileFailed", t,
                    "conversationId" to conversationId,
                )
            }
        if (state.value.unresolvedToolCallIds().isNotEmpty()) {
            scheduleSweepIfUnresolved(clean = true)
        }
    }

    /** Submit terminal settlement; the reducer rechecks unresolved calls at commit time. */
    private suspend fun settleAsFailed(generation: Long, lifecycleEpoch: Long, callIds: Set<String>) {
        if (generation != sweepGeneration) return
        val ack = processor.submit(
            TimelineMutation.SettleDanglingToolCalls(generation, lifecycleEpoch, callIds),
        ) as? TimelineProcessorAck.Applied ?: return
        val result = ack.result as? TimelineReductionResult.DanglingToolCallsSettled ?: return
        result.callIds.forEach { id ->
            Telemetry.event(
                "TimelineSync", "danglingToolResolve.settledFailed",
                "conversationId" to conversationId,
                "toolCallId" to id,
            )
        }
    }

    companion object {
        const val REASON = "dangling-tool-resolve"
        const val HYDRATION_REASON = "dangling-tool-resolve"
        const val NO_RESULT_MESSAGE = "No tool result recorded"
        val DEFAULT_BACKOFF_MS = listOf(2_000L, 5_000L, 15_000L, 30_000L)
    }
}

/**
 * Call ids belonging to confirmed TOOL_CALL events that have no attached
 * return yet. The only signal this resolver ever trusts to decide "is this
 * still dangling" — driven entirely by the canonical-record-backed
 * [TimelineEvent.Confirmed.toolReturnContentByCallId] map, never a local
 * guess.
 */
fun Timeline.unresolvedToolCallIds(): Set<String> {
    val ids = mutableSetOf<String>()
    events.forEach { ev ->
        if (ev !is TimelineEvent.Confirmed || ev.messageType != TimelineMessageType.TOOL_CALL) return@forEach
        ev.toolCalls.forEach { tc ->
            val id = tc.effectiveId.takeIf { it.isNotBlank() } ?: return@forEach
            if (!ev.toolReturnContentByCallId.containsKey(id)) ids += id
        }
    }
    return ids
}
