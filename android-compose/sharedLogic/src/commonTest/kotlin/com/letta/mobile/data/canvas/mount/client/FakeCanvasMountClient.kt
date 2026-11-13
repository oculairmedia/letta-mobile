package com.letta.mobile.data.canvas.mount.client

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A host's mounts in memory. Writes store the canvas per conversation; [answers] and [writeAnswers]
 * script the next replies (a denial, an outage, an unknown outcome) before the stored state is used.
 */
class FakeCanvasMountClient(supported: Boolean = true) : CanvasMountClient {
    val support = MutableStateFlow(supported)
    override val supported: StateFlow<Boolean> get() = support

    /** What the host holds per conversation: the conversation's own choice. */
    val stored = mutableMapOf<ConversationCanvasTarget, CanvasId>()

    /** Each agent's default canvas, shown where the conversation has made no choice. */
    val agentDefaults = mutableMapOf<AgentId, CanvasId>()

    /** Scripted replies to the next resolves, used first. */
    val answers = ArrayDeque<CanvasMountLookup>()

    /** Scripted replies to the next writes, used first; [applyScripted] still stores the write. */
    val writeAnswers = ArrayDeque<CanvasMountChange>()

    /** Whether a scripted write reply still stores the write (an applied write whose reply was lost). */
    var applyScripted = false

    /** When set, the next resolve waits for it before answering (a slow host). */
    var slowResolve: CompletableDeferred<Unit>? = null

    /** Runs as each write reaches the host (the connection dropping mid-write, say). */
    var onWrite: () -> Unit = {}

    /** Mounts the host pushes. */
    val pushed = MutableSharedFlow<CanvasId>(extraBufferCapacity = PUSH_BUFFER)

    var resolves = 0
        private set
    var writes = 0
        private set

    override suspend fun resolve(target: ConversationCanvasTarget): CanvasMountLookup {
        resolves++
        slowResolve?.also { slowResolve = null }?.await()
        return answers.removeFirstOrNull()
            ?: stored[target]?.let { CanvasMountLookup.Found(it, CanvasMountSource.Binding, CanvasMountDurability.Durable) }
            ?: agentDefaults[target.agentId]?.let { CanvasMountLookup.Found(it, CanvasMountSource.Default) }
            ?: CanvasMountLookup.None
    }

    override suspend fun mount(target: ConversationCanvasTarget, canvas: CanvasId): CanvasMountChange = store(target, canvas)

    override suspend fun unmount(target: ConversationCanvasTarget): CanvasMountChange = store(target, target.ownCanvas)

    override fun changes(target: ConversationCanvasTarget): Flow<CanvasId> = pushed

    private fun store(target: ConversationCanvasTarget, canvas: CanvasId): CanvasMountChange {
        writes++
        onWrite()
        val scripted = writeAnswers.removeFirstOrNull()
        if (scripted == null || applyScripted) stored[target] = canvas
        return scripted ?: CanvasMountChange.Applied(canvas, CanvasMountDurability.Durable)
    }

    private companion object {
        const val PUSH_BUFFER = 8
    }
}
