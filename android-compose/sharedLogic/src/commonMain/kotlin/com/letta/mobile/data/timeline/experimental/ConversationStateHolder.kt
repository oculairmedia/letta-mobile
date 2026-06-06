package com.letta.mobile.data.timeline.experimental

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.PendingIngestNotification
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineReducerInput
import com.letta.mobile.data.timeline.TimelineReducerOutput
import com.letta.mobile.data.timeline.TimelineSyncEvent
import com.letta.mobile.data.timeline.reduceStreamFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

/**
 * letta-mobile-oc8j Phase 1 → Phase 3a.
 *
 * Flow-backed `ConversationStateHolder` for a single conversation. Folds an
 * upstream `Flow<LettaMessage>` (WS frame stream) into a `Timeline` using the
 * pure [reduceStreamFrame] extracted in letta-mobile-bfqgi.
 *
 * Exposes three output surfaces — Phase 2 wiring (`letta-mobile-t0vha`)
 * routes the loop's stream-ingest entry point through these instead of
 * mutating loop-local state directly:
 *
 *  - [state] : StateFlow<Timeline> — the reduced timeline.
 *  - [events] : SharedFlow<TimelineSyncEvent> — per-frame events to emit
 *    on the loop's `_events` SharedFlow.
 *  - [notifications] : SharedFlow<PendingIngestNotification> — per-frame
 *    notifications to dispatch via `TimelineIngestNotificationDispatcher`.
 *
 * ## Fold via Flow.scan
 *
 * The fold is `frames.scan(seed, reduce)` so the reducer stays a pure
 * function and the test surface (TimelineStreamReducerTest +
 * TimelineSyncLoopTest fixtures) keeps applying byte-equal. This holder used
 * to expose [state] through Molecule, but each cached conversation loop then
 * owned a background Compose runtime. ANR traces showed those runtimes
 * contending with the UI on the global Compose snapshot apply locks during
 * chat/list scroll. The shadow holder is not UI and not authoritative, so it
 * must not participate in Compose snapshot notification dispatch.
 *
 * ## Hydration seed (Phase 3a, letta-mobile-bmgro)
 *
 * The fold's starting Timeline is sourced from [hydrationSeed]. Each
 * emission rebases the fold via [flatMapLatest]: the scan restarts from
 * the new hydrated Timeline with an empty pending-tool-returns map. This
 * mirrors the imperative path's semantics — `TimelineSyncLoop.hydrate()`
 * replaces `_state` wholesale and a re-hydrate is effectively a cold start
 * for stream ingest. Before this, the holder permanently held
 * `Timeline(empty)` and parity telemetry showed `matched=false` for the
 * first N events after every cold-start. Phase 3b will be the actual flip
 * of authoritative state to the holder; this bead only makes parity
 * observable.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationStateHolder(
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val frames: Flow<LettaMessage>,
    private val hydrationSeed: Flow<Timeline> = flowOf(Timeline(conversationId = conversationId)),
) {

    private fun seedOutputFor(timeline: Timeline) = TimelineReducerOutput(
        next = timeline,
        updatedPendingToolReturnsByCallId = emptyMap(),
        emittedEvents = emptyList(),
        notification = null,
    )

    private val initialSeedOutput = seedOutputFor(Timeline(conversationId = conversationId))

    /**
     * Per-frame reducer output stream. Eager-shared so a single fold runs
     * for the lifetime of the holder. Each [hydrationSeed] emission
     * rebases the fold via `flatMapLatest`, restarting the scan from the
     * new hydrated Timeline.
     */
    private val reducerOutputs: StateFlow<TimelineReducerOutput> = hydrationSeed
        .flatMapLatest { hydrated ->
            frames.scan(seedOutputFor(hydrated)) { acc, frame ->
                reduceStreamFrame(
                    TimelineReducerInput(
                        prev = acc.next,
                        frame = frame,
                        pendingToolReturnsByCallId = acc.updatedPendingToolReturnsByCallId,
                    )
                )
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, initialSeedOutput)

    /** Reduced timeline state without involving Compose snapshot machinery. */
    val state: StateFlow<Timeline> = reducerOutputs
        .map { it.next }
        .stateIn(scope, SharingStarted.Eagerly, initialSeedOutput.next)

    /**
     * Per-frame events emitted by the reducer. Drops the seed (whose
     * emittedEvents list is always empty) so subscribers don't see a
     * spurious empty emission at attach time.
     */
    private val _events = MutableSharedFlow<TimelineSyncEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<TimelineSyncEvent> = _events.asSharedFlow()

    /**
     * Per-frame notifications. Same drop-seed semantics as [events].
     */
    private val _notifications = MutableSharedFlow<PendingIngestNotification>(extraBufferCapacity = 16)
    val notifications: SharedFlow<PendingIngestNotification> = _notifications.asSharedFlow()

    init {
        // Fan-out reducer-output side channels (events + notifications)
        // into their own SharedFlows. The fold itself is owned by
        // reducerOutputs.stateIn — this just splits the outputs.
        reducerOutputs
            .drop(1) // skip the seed (no events / notification on the initial value)
            .onEach { output ->
                output.emittedEvents.forEach { _events.emit(it) }
                output.notification?.let { _notifications.emit(it) }
            }
            .launchIn(scope)
    }
}
