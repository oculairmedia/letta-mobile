package com.letta.mobile.data.timeline.experimental

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.timeline.PendingIngestNotification
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineHydrationReducer
import com.letta.mobile.data.timeline.TimelineReducerInput
import com.letta.mobile.data.timeline.TimelineReducerOutput
import com.letta.mobile.data.timeline.TimelineSyncEvent
import com.letta.mobile.data.timeline.reduceStreamFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

/**
 * letta-mobile-oc8j Phase 1 → Phase 2.
 *
 * Molecule-backed `ConversationStateHolder` for a single conversation.
 * Folds an upstream `Flow<LettaMessage>` (WS frame stream) into a
 * `Timeline` using the pure [reduceStreamFrame] extracted in
 * letta-mobile-bfqgi.
 *
 * Exposes three output surfaces — Phase 2 wiring (`letta-mobile-t0vha`)
 * routes the loop's stream-ingest entry point through these instead of
 * mutating loop-local state directly:
 *
 *  - [state] : StateFlow<Timeline> — the reduced timeline, Molecule-driven.
 *  - [events] : SharedFlow<TimelineSyncEvent> — per-frame events to emit
 *    on the loop's `_events` SharedFlow.
 *  - [notifications] : SharedFlow<PendingIngestNotification> — per-frame
 *    notifications to dispatch via `TimelineIngestNotificationDispatcher`.
 *
 * ## Fold via Flow.scan, derivation via Molecule
 *
 * The fold is `frames.scan(seed, reduce)` so the reducer stays a pure
 * function and the test surface (TimelineStreamReducerTest +
 * TimelineSyncLoopTest fixtures) keeps applying byte-equal. The Molecule
 * `@Composable present()` reads the scanned outputs via `collectAsState`,
 * so Phase 2's downstream composition (active runs banner, agent state,
 * A2UI surface registry) can join in the same derivation block without
 * restructuring the call site.
 *
 * ## RecompositionMode
 *
 * `RecompositionMode.Immediate` because consumers typically run on
 * `Dispatchers.Main.immediate`, where `ContextClock` crashes for lack of
 * a `MonotonicFrameClock`. Same gotcha `ConfigListViewModel` documents.
 */
internal class ConversationStateHolder(
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val frames: Flow<LettaMessage>,
    initial: Timeline = Timeline(conversationId = conversationId),
) {

    private val seedOutput = TimelineReducerOutput(
        next = initial,
        updatedPendingToolReturnsByCallId = emptyMap(),
        emittedEvents = emptyList(),
        notification = null,
    )

    /**
     * Per-frame reducer output stream. Eager-shared so a single fold runs
     * for the lifetime of the holder, with downstream consumers seeing
     * each [reduceStreamFrame] result.
     */
    private val reducerOutputs: StateFlow<TimelineReducerOutput> = frames
        .scan(seedOutput) { acc, frame ->
            reduceStreamFrame(
                TimelineReducerInput(
                    prev = acc.next,
                    frame = frame,
                    pendingToolReturnsByCallId = acc.updatedPendingToolReturnsByCallId,
                )
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, seedOutput)

    /**
     * Reduced timeline state. Mirrors [reducerOutputs] but exposed as
     * a Molecule-driven `StateFlow<Timeline>` so future Phase 2+ surfaces
     * can compose with other Flows declaratively.
     */
    val state: StateFlow<Timeline> = scope.launchMolecule(mode = RecompositionMode.Immediate) {
        present()
    }

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

    @Composable
    private fun present(): Timeline {
        val output by reducerOutputs.collectAsState()
        return output.next
    }

    companion object {
        /**
         * Convenience constructor that wraps a non-Flow hydration list into
         * the holder via [TimelineHydrationReducer]. Mirrors the cold-start
         * shape `TimelineSyncLoop.hydrate()` uses, so parity tests can
         * exercise hydration alongside streaming.
         */
        internal fun withHydration(
            conversationId: String,
            scope: CoroutineScope,
            frames: Flow<LettaMessage>,
            hydration: List<LettaMessage>,
        ): ConversationStateHolder {
            val hydrated = TimelineHydrationReducer.reduce(
                conversationId = conversationId,
                serverMessagesChronological = hydration,
                timelineBeforeFetch = Timeline(conversationId = conversationId),
                currentTimeline = Timeline(conversationId = conversationId),
                diskRecords = emptyList(),
            ).timeline
            return ConversationStateHolder(
                conversationId = conversationId,
                scope = scope,
                frames = frames,
                initial = hydrated,
            )
        }
    }
}
