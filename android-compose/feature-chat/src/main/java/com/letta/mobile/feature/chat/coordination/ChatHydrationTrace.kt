package com.letta.mobile.feature.chat.coordination

import com.letta.mobile.util.Telemetry

/**
 * Debug-only, count-only correlation for the initial chat presentation path.
 * Content, tool calls, and A2UI payloads intentionally never enter this API.
 */
internal object ChatHydrationTrace {
    private const val TAG = "ChatHydration"
    private const val UNKNOWN = "unknown"
    private const val MAX_ACTIVE_TRACES = 16

    data class Identity(
        val agentId: String?,
        val conversationId: String,
        val backendId: String = UNKNOWN,
        val runtimeId: String = UNKNOWN,
    )

    data class Generation internal constructor(
        internal val id: Long,
        internal val identity: Identity,
    )

    private data class State(
        val generation: Generation,
        val startedAtNs: Long,
        var sourceReady: Boolean = false,
        var published: Int = 0,
        var layouts: Int = 0,
        var scrolls: Int = 0,
        var thinkingTransitions: Int = 0,
        var stale: Int = 0,
        var activity: Boolean = false,
        var settled: Boolean = false,
    )

    private var nextGeneration = 0L
    private val active = linkedMapOf<Identity, State>()

    fun begin(identity: Identity, reuseIfActive: Boolean = false): Generation = synchronized(this) {
        if (reuseIfActive) active[identity]?.generation?.let { return@synchronized it }
        Generation(++nextGeneration, identity).also { generation ->
            active[identity] = State(generation = generation, startedAtNs = System.nanoTime())
            while (active.size > MAX_ACTIVE_TRACES) active.remove(active.entries.first().key)
            emit(active.getValue(identity), "hydration.started", "commitReason" to "conversation_open")
        }
    }

    fun sourceReady(generation: Generation, source: String, count: Int) = mutate(generation) { state ->
        state.sourceReady = true
        emit(state, "source_ready", "source" to safeValue(source), "sourceLatencyMs" to elapsedMs(state), "sourceCount" to count.coerceAtLeast(0))
        settleIfReady(state)
    }

    fun sourceUnavailable(generation: Generation, source: String) = mutate(generation) { state ->
        emit(state, "source_unavailable", "source" to safeValue(source), "sourceLatencyMs" to elapsedMs(state), "sourceCount" to 0)
    }

    fun reconcileStarted(generation: Generation, reason: String) = mutate(generation) { state ->
        emit(state, "reconcile_started", "commitReason" to safeValue(reason))
    }

    fun reconcileCompleted(generation: Generation, reason: String) = mutate(generation) { state ->
        emit(state, "reconcile_completed", "commitReason" to safeValue(reason))
        settleIfReady(state)
    }

    fun presentationPublished(
        generation: Generation,
        commitReason: String,
        messageCount: Int,
        missingOptionalSources: String,
    ) = mutate(generation) { state ->
        state.published++
        emit(
            state,
            "presentation_published",
            "commitReason" to safeValue(commitReason),
            "messageCount" to messageCount.coerceAtLeast(0),
            "missingOptionalSources" to safeValue(missingOptionalSources),
        )
        settleIfReady(state)
    }

    fun activityChanged(generation: Generation, active: Boolean, reason: String) = mutate(generation) { state ->
        state.thinkingTransitions++
        state.activity = active
        emit(state, "activity_changed", "activity" to active, "commitReason" to safeValue(reason))
        settleIfReady(state)
    }

    fun firstLayout(generation: Generation, renderItemCount: Int) = mutate(generation) { state ->
        state.layouts++
        if (state.layouts == 1) {
            emit(state, "first_layout", "renderItemCount" to renderItemCount)
        }
        settleIfReady(state)
    }

    fun scrollInitialized(generation: Generation, correction: String) = mutate(generation) { state ->
        state.scrolls++
        emit(state, "scroll_initialized", "scrollCorrection" to safeValue(correction))
        settleIfReady(state)
    }

    fun current(conversationId: String?): Generation? = synchronized(this) {
        if (conversationId == null) null else active.entries.lastOrNull { it.key.conversationId == conversationId }?.value?.generation
    }

    internal fun clearForTest() = synchronized(this) {
        active.clear()
        nextGeneration = 0L
    }

    private fun mutate(generation: Generation, block: (State) -> Unit) = synchronized(this) {
        val state = active[generation.identity]
        if (state == null || state.generation.id != generation.id) {
            // A cancelled observer can still complete a dispatcher hop. Preserve its
            // generation in the event so a trace can prove that it was stale.
            val staleState = State(generation, System.nanoTime(), stale = (state?.stale ?: 0) + 1)
            emit(staleState, "presentation_published", "commitReason" to "stale_generation", "messageCount" to 0, "missingOptionalSources" to "none", "isStale" to true)
            return@synchronized
        }
        block(state)
    }

    private fun settleIfReady(state: State) {
        if (state.settled || !state.sourceReady || state.published == 0 || state.layouts == 0 || state.scrolls == 0 || state.activity) return
        state.settled = true
        emit(state, "settled", "commitReason" to "initial_frame_settled")
    }

    private fun emit(state: State, name: String, vararg attrs: Pair<String, Any?>) {
        if (!Telemetry.isChatHydrationTraceEnabled()) return
        Telemetry.event(
            TAG,
            name,
            "generation" to state.generation.id,
            "agentId" to (state.generation.identity.agentId ?: UNKNOWN),
            "conversationId" to state.generation.identity.conversationId,
            "backendId" to state.generation.identity.backendId,
            "runtimeId" to state.generation.identity.runtimeId,
            "elapsedMs" to elapsedMs(state),
            "publicationCount" to state.published,
            "layoutPassCount" to state.layouts,
            "scrollCorrectionCount" to state.scrolls,
            "thinkingTransitionCount" to state.thinkingTransitions,
            "staleCount" to state.stale,
            "missingOptionalSources" to "none",
            "commitReason" to "unspecified",
            *attrs,
            level = Telemetry.Level.DEBUG,
        )
    }

    private fun elapsedMs(state: State): Long = (System.nanoTime() - state.startedAtNs).coerceAtLeast(0L) / 1_000_000L

    // These fields describe reducer states, not user-controlled content. Unknown values are redacted.
    private fun safeValue(value: String): String = value.takeIf {
        it in setOf(
            "timeline", "a2ui", "none", "open", "conversation_open", "initial_frame_settled",
            "stale_generation", "presence_only", "projection_presence", "conversation_reset",
            "follow_latest_reset", "Full", "AppendTail", "ReplaceTail", "redacted",
        )
    } ?: "redacted"
}
