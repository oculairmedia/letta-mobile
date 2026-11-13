package com.letta.mobile.data.canvas.mount.client

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.ConversationId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Which board a conversation shows, as far as this app knows right now. */
sealed interface ConversationCanvasState {
    /** Still asking the host; the conversation shows its own board meanwhile. */
    data object Resolving : ConversationCanvasState

    /** The conversation's own board (nothing mounted, or the host has no mounts). */
    data object Own : ConversationCanvasState

    /** [canvas] is mounted for the conversation, from [source]. */
    data class Mounted(val canvas: CanvasId, val source: CanvasMountSource) : ConversationCanvasState

    /** The host could not say, for [problem]; the conversation shows its own board. */
    data class Unavailable(val problem: CanvasMountProblem) : ConversationCanvasState
}

/** The mounted canvas, or null when the conversation shows its own board. */
val ConversationCanvasState.mountedCanvas: CanvasId? get() = (this as? ConversationCanvasState.Mounted)?.canvas

/**
 * The one answer to "which canvas does this conversation show", for the board on screen and for
 * the agent's canvas tools alike: the host's mount when the host offers mounts and has one, else
 * the conversation's own board, exactly as before mounts existed.
 *
 * Opening a conversation never waits on the host: [state] starts at
 * [ConversationCanvasState.Resolving] (the own board shows) and moves on when the host answers.
 * A conversation is resolved again each time it is observed and after each of this app's own
 * writes; a change the host pushes ([CanvasMountClient.changes]) is taken in turn with them, and
 * only while the host offers mounts. An answer of `outcome_unknown` is never trusted: the
 * conversation is resolved again, and only the host's own answer confirms the write.
 *
 * Calls are serialized, so a slow answer that was asked before a write or a pushed change can never
 * overwrite it.
 * One instance per window (and the tools it serves), so every view agrees.
 */
class ConversationCanvasResolver(private val client: CanvasMountClient) {
    private val known = MutableStateFlow<Map<ConversationCanvasTarget, ConversationCanvasState>>(emptyMap())
    private val turn = Mutex()

    /** True while the host offers canvas mounts. */
    val supported: StateFlow<Boolean> get() = client.supported

    /** Who the mounts reach: the host's, or (a host without mounts) this device's only. */
    val reach: Flow<CanvasMountReach> get() = client.reach

    /** What is known about [target] now, without asking. */
    fun current(target: ConversationCanvasTarget): ConversationCanvasState =
        known.value[target] ?: ConversationCanvasState.Resolving

    /** [target]'s board as it changes: asked when collected and whenever the host's support or the mounts' reach changes. */
    fun state(target: ConversationCanvasTarget): Flow<ConversationCanvasState> = channelFlow {
        launch { combine(client.supported, client.reach, ::Pair).collect { refresh(target) } }
        launch { client.changes(target).collect { accept(target, it) } }
        known.map { it[target] ?: ConversationCanvasState.Resolving }.distinctUntilChanged().collect { send(it) }
    }

    /**
     * The canvas mounted for [conversationId] of [agentId] now, asking the host; null for its own board.
     * The agent's canvas tools ask this, so only the host's answer counts ([CanvasMountClient.resolveOnHost]):
     * a mount held on this device only answers null, even when the host drops in the middle of the
     * question, and the tools keep the conversation's own canvas, as the screen tells the person.
     */
    suspend fun mountedCanvas(agentId: AgentId, conversationId: ConversationId): CanvasId? {
        if (!client.supported.value) return null
        val target = ConversationCanvasTarget(agentId, conversationId)
        return stateOf(target, lookup(target, client::resolveOnHost)).mountedCanvas
    }

    /** Asks the host for [target]'s board now and publishes the answer. */
    suspend fun refresh(target: ConversationCanvasTarget): ConversationCanvasState = turn.withLock { refreshLocked(target, keep = false) }

    /** Mounts [canvas] for [target] only; the board follows when it is [CanvasMountChange.Applied]. */
    suspend fun mount(target: ConversationCanvasTarget, canvas: CanvasId): CanvasMountChange =
        write(target, canvas) { client.mount(target, canvas) }

    /** Puts [target] back on its own board. */
    suspend fun unmount(target: ConversationCanvasTarget): CanvasMountChange =
        write(target, target.ownCanvas) { client.unmount(target) }

    /**
     * Takes the board the host pushed for [target] (its own canvas once unmounted), in turn with
     * lookups and writes; ignored once the host no longer offers mounts.
     */
    suspend fun accept(target: ConversationCanvasTarget, canvas: CanvasId) = turn.withLock {
        if (client.supported.value) publish(target, stateOf(target, CanvasMountLookup.Found(canvas, CanvasMountSource.Binding)))
    }

    private suspend fun write(
        target: ConversationCanvasTarget,
        wanted: CanvasId,
        call: suspend () -> CanvasMountChange,
    ): CanvasMountChange = turn.withLock {
        if (!client.supported.value) return@withLock UNSUPPORTED
        when (val change = call()) {
            is CanvasMountChange.Applied -> change.also { applied(target, it) }
            is CanvasMountChange.Failed -> settle(target, wanted, change)
        }
    }

    /** Shows this app's own write at once, then confirms it with the host (keeping it if the host cannot say). */
    private suspend fun applied(target: ConversationCanvasTarget, change: CanvasMountChange.Applied) {
        publish(target, stateOf(target, CanvasMountLookup.Found(change.canvas, CanvasMountSource.Binding)))
        refreshLocked(target, keep = true)
    }

    /**
     * After an unknown outcome, what the host now holds decides whether the write landed. Only the
     * host's own answer confirms it: when the host cannot say (or has gone, leaving this device's
     * mounts to answer) the outcome stays unknown and the board shows whatever answers now.
     */
    private suspend fun settle(target: ConversationCanvasTarget, wanted: CanvasId, failed: CanvasMountChange.Failed): CanvasMountChange {
        if (failed.problem != CanvasMountProblem.OutcomeUnknown) return failed
        val held = lookup(target, client::resolveOnHost)
        if (held is CanvasMountLookup.Failed) return failed.also { refreshLocked(target, keep = false) }
        val now = stateOf(target, held).also { publish(target, it) }
        return if (shownCanvas(target, now) == wanted) CanvasMountChange.Applied(wanted) else failed
    }

    /** [keep]: a failed answer leaves what is known (after this app's own write) instead of falling back. */
    private suspend fun refreshLocked(target: ConversationCanvasTarget, keep: Boolean): ConversationCanvasState {
        if (!client.supported.value) return ConversationCanvasState.Own.also { publish(target, it) }
        val answer = lookup(target)
        if (keep && answer is CanvasMountLookup.Failed) return current(target)
        return stateOf(target, answer).also { publish(target, it) }
    }

    private suspend fun lookup(
        target: ConversationCanvasTarget,
        ask: suspend (ConversationCanvasTarget) -> CanvasMountLookup = client::resolve,
    ): CanvasMountLookup {
        repeat(ASKS) {
            val answer = ask(target)
            if (answer != OUTCOME_UNKNOWN) return answer
        }
        return CanvasMountLookup.Failed(CanvasMountProblem.Unavailable(CanvasMountUnavailableReason.StillUnknown))
    }

    private fun publish(target: ConversationCanvasTarget, state: ConversationCanvasState) {
        known.update { it + (target to state) }
    }

    private companion object {
        /** How many times an `outcome_unknown` answer is asked again before giving up. */
        const val ASKS = 3
        val OUTCOME_UNKNOWN = CanvasMountLookup.Failed(CanvasMountProblem.OutcomeUnknown)
        val UNSUPPORTED = CanvasMountChange.Failed(CanvasMountProblem.Unavailable(CanvasMountUnavailableReason.Unsupported))

        fun stateOf(target: ConversationCanvasTarget, answer: CanvasMountLookup): ConversationCanvasState = when (answer) {
            is CanvasMountLookup.Found -> mountedOrOwn(target, answer)
            CanvasMountLookup.None -> ConversationCanvasState.Own
            is CanvasMountLookup.Failed -> ConversationCanvasState.Unavailable(answer.problem)
        }

        /** The conversation's own canvas mounted on itself is no mount at all. */
        fun mountedOrOwn(target: ConversationCanvasTarget, found: CanvasMountLookup.Found): ConversationCanvasState = when (found.canvas) {
            target.ownCanvas -> ConversationCanvasState.Own
            else -> ConversationCanvasState.Mounted(found.canvas, found.source)
        }

        /** The board [state] puts on screen; null while unknown. */
        fun shownCanvas(target: ConversationCanvasTarget, state: ConversationCanvasState): CanvasId? = when (state) {
            ConversationCanvasState.Own -> target.ownCanvas
            is ConversationCanvasState.Mounted -> state.canvas
            else -> null
        }
    }
}
