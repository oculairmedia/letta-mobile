package com.letta.mobile.data.timeline.experimental

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.cash.molecule.RecompositionMode
import app.cash.molecule.launchMolecule
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.ToolReturnMessage
import com.letta.mobile.data.timeline.Timeline
import com.letta.mobile.data.timeline.TimelineHydrationReducer
import com.letta.mobile.data.timeline.TimelineReducerInput
import com.letta.mobile.data.timeline.reduceStreamFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn

/**
 * letta-mobile-oc8j Phase 1 prototype.
 *
 * Molecule-backed `ConversationStateHolder` for a single conversation.
 * Folds the WS frame stream (and an optional cold-start hydration) into a
 * `Timeline` `StateFlow` using the pure [reduceStreamFrame] extracted in
 * letta-mobile-bfqgi as the per-frame reducer.
 *
 * Lives under `experimental/` and runs alongside the imperative
 * `TimelineSyncIngest` path — no production call site wired to it yet.
 * The next bead's parity test feeds the same fixture into both shapes and
 * asserts byte-equal output `Timeline`s. If parity holds and there's no
 * regression in perceived latency / memory, Phase 2 will migrate
 * `TimelineSyncLoop` to consume this holder instead.
 *
 * ## Shape
 *
 *  - Construction takes the conversation id and the `Flow<LettaMessage>`
 *    representing the live WS frame stream for that conversation.
 *  - Optional hydration is folded into the initial state via
 *    [TimelineHydrationReducer]'s existing pure reduce — the same logic
 *    that the imperative path uses on cold start. The holder accepts a
 *    pre-hydrated `initial` `Timeline` so the hydration call site stays
 *    where it is (REST `/messages` fetch lives in `TimelineSyncLoop`).
 *  - `state: StateFlow<Timeline>` is the single observable output. It is
 *    Molecule-driven so future Phase 2 composition (active runs banner,
 *    agent state, A2UI surface registry) can read other Flows in the same
 *    `@Composable present()` without re-architecting.
 *
 * ## Why fold via Flow.scan, not Molecule itself
 *
 * Molecule's `@Composable` model is pure derivation per recomposition — it
 * has no native concept of "accumulate state across emissions." Doing the
 * fold inside `@Composable` would require `LaunchedEffect` + `mutableStateOf`
 * and would couple the reducer to the composition lifecycle, making the
 * pure reducer harder to test. Instead the fold lives upstream as
 * `frames.scan(initial, ::reduceStreamFrame)` and the `@Composable present()`
 * just collects the most recent state via `collectAsState`. Molecule's
 * value-add is composing this state with other Flows in Phase 2; the fold
 * stays a pure function.
 *
 * ## RecompositionMode
 *
 * Uses `RecompositionMode.Immediate` because the consuming scope typically
 * runs on `Dispatchers.Main.immediate` (no `MonotonicFrameClock`), where
 * `ContextClock` crashes on first composition. Same gotcha
 * `ConfigListViewModel` already documents.
 */
internal class ConversationStateHolder(
    private val conversationId: String,
    private val scope: CoroutineScope,
    private val frames: Flow<LettaMessage>,
    initial: Timeline = Timeline(conversationId = conversationId),
) {

    /**
     * Reduced state stream. Each WS frame triggers one `reduceStreamFrame`
     * invocation upstream; Molecule then re-emits the resulting `Timeline`
     * to subscribers. Conflated by the underlying `StateFlow`.
     */
    private val reduced: StateFlow<ReducedFold> = frames
        .scan(ReducedFold(timeline = initial)) { acc, frame ->
            val output = reduceStreamFrame(
                TimelineReducerInput(
                    prev = acc.timeline,
                    frame = frame,
                    pendingToolReturnsByCallId = acc.pendingToolReturnsByCallId,
                )
            )
            ReducedFold(
                timeline = output.next,
                pendingToolReturnsByCallId = output.updatedPendingToolReturnsByCallId,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, ReducedFold(timeline = initial))

    /**
     * Observable timeline state for this conversation. Subscribers see one
     * emission per inbound frame plus the seed.
     *
     * Molecule-backed: `@Composable present()` reads [reduced] via
     * `collectAsState` so future Phase 2 can compose other Flows in the
     * same derivation block without restructuring the call site.
     */
    val state: StateFlow<Timeline> = scope.launchMolecule(mode = RecompositionMode.Immediate) {
        present()
    }

    @Composable
    private fun present(): Timeline {
        val fold by reduced.collectAsState()
        return fold.timeline
    }

    private data class ReducedFold(
        val timeline: Timeline,
        val pendingToolReturnsByCallId: Map<String, ToolReturnMessage> = emptyMap(),
    )

    companion object {
        /**
         * Convenience constructor that wraps a non-Flow hydration list into
         * the holder via [TimelineHydrationReducer]. Mirrors the cold-start
         * shape that `TimelineSyncLoop.hydrate()` uses, so Phase 1 parity
         * tests can exercise hydration alongside streaming.
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
