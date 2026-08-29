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

    private class Registry {
        var nextGeneration = 0L
        val active: MutableMap<Identity, State> = linkedMapOf()
    }

    private data class TraceDetails(
        val commitReason: String = "unspecified",
        val source: String = "none",
        val sourceLatencyMs: Long = -1L,
        val sourceCount: Int = -1,
        val messageCount: Int = -1,
        val missingOptionalSources: String = "none",
        val activity: Boolean = false,
        val renderItemCount: Int = -1,
        val scrollCorrection: String = "none",
        val isStale: Boolean = false,
    )

    private val registry = Registry()

    fun begin(identity: Identity, reuseIfActive: Boolean = false): Generation = synchronized(this) {
        if (reuseIfActive) registry.active[identity]?.generation?.let { return@synchronized it }
        Generation(++registry.nextGeneration, identity).also { generation ->
            registry.active[identity] = State(generation = generation, startedAtNs = System.nanoTime())
            while (registry.active.size > MAX_ACTIVE_TRACES) registry.active.remove(registry.active.entries.first().key)
            emit(registry.active.getValue(identity), "hydration.started", TraceDetails(commitReason = "conversation_open"))
        }
    }

    fun sourceReady(generation: Generation, source: String, count: Int) = mutate(generation) { state ->
        state.sourceReady = true
        emit(
            state,
            "source_ready",
            TraceDetails(
                source = safeValue(source),
                sourceLatencyMs = elapsedMs(state),
                sourceCount = count.coerceAtLeast(0),
            ),
        )
        settleIfReady(state)
    }

    fun sourceUnavailable(generation: Generation, source: String) = mutate(generation) { state ->
        emit(
            state,
            "source_unavailable",
            TraceDetails(source = safeValue(source), sourceLatencyMs = elapsedMs(state), sourceCount = 0),
        )
    }

    fun reconcileStarted(generation: Generation, reason: String) = mutate(generation) { state ->
        emit(state, "reconcile_started", TraceDetails(commitReason = safeValue(reason)))
    }

    fun reconcileCompleted(generation: Generation, reason: String) = mutate(generation) { state ->
        emit(state, "reconcile_completed", TraceDetails(commitReason = safeValue(reason)))
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
            TraceDetails(
                commitReason = safeValue(commitReason),
                messageCount = messageCount.coerceAtLeast(0),
                missingOptionalSources = safeValue(missingOptionalSources),
            ),
        )
        settleIfReady(state)
    }

    fun activityChanged(generation: Generation, active: Boolean, reason: String) = mutate(generation) { state ->
        state.thinkingTransitions++
        state.activity = active
        emit(state, "activity_changed", TraceDetails(activity = active, commitReason = safeValue(reason)))
        settleIfReady(state)
    }

    fun firstLayout(generation: Generation, renderItemCount: Int) = mutate(generation) { state ->
        state.layouts++
        if (state.layouts == 1) {
            emit(state, "first_layout", TraceDetails(renderItemCount = renderItemCount))
        }
        settleIfReady(state)
    }

    fun scrollInitialized(generation: Generation, correction: String) = mutate(generation) { state ->
        state.scrolls++
        emit(state, "scroll_initialized", TraceDetails(scrollCorrection = safeValue(correction)))
        settleIfReady(state)
    }

    fun current(conversationId: String?): Generation? = synchronized(this) {
        if (conversationId == null) {
            null
        } else {
            registry.active.entries.lastOrNull { it.key.conversationId == conversationId }?.value?.generation
        }
    }

    internal fun clearForTest() = synchronized(this) {
        registry.active.clear()
        registry.nextGeneration = 0L
    }

    private fun mutate(generation: Generation, block: (State) -> Unit) = synchronized(this) {
        val state = registry.active[generation.identity]
        if (state == null || state.generation.id != generation.id) {
            val staleState = State(generation, System.nanoTime(), stale = (state?.stale ?: 0) + 1)
            emit(
                staleState,
                "presentation_published",
                TraceDetails(
                    commitReason = "stale_generation",
                    messageCount = 0,
                    missingOptionalSources = "none",
                    isStale = true,
                ),
            )
            return@synchronized
        }
        block(state)
    }

    private fun settleIfReady(state: State) {
        if (!state.canSettle()) return
        state.settled = true
        emit(state, "settled", TraceDetails(commitReason = "initial_frame_settled"))
    }

    private fun State.canSettle(): Boolean = !settled && hasInitialFrame() && !activity

    private fun State.hasInitialFrame(): Boolean = sourceReady && published > 0 && layouts > 0 && scrolls > 0

    private fun emit(state: State, name: String, details: TraceDetails) {
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
            "missingOptionalSources" to details.missingOptionalSources,
            "commitReason" to details.commitReason,
            "source" to details.source,
            "sourceLatencyMs" to details.sourceLatencyMs,
            "sourceCount" to details.sourceCount,
            "messageCount" to details.messageCount,
            "activity" to details.activity,
            "renderItemCount" to details.renderItemCount,
            "scrollCorrection" to details.scrollCorrection,
            "isStale" to details.isStale,
            level = Telemetry.Level.DEBUG,
        )
    }

    private fun elapsedMs(state: State): Long = (System.nanoTime() - state.startedAtNs).coerceAtLeast(0L) / 1_000_000L

    // These fields describe reducer states, not user-controlled content. Unknown values are redacted.
    private fun safeValue(value: String): String = value.takeIf {
        it in setOf(
            "timeline", "a2ui", "none", "open", "conversation_open", "initial_frame_settled",
            "stale_generation", "presence_only", "projection_presence", "conversation_reset",
            "follow_latest_reset", "initial_restore", "coalesced_follow", "user_controlled",
            "stable_viewport_settled", "Full", "AppendTail", "ReplaceTail", "redacted",
        )
    } ?: "redacted"
}
