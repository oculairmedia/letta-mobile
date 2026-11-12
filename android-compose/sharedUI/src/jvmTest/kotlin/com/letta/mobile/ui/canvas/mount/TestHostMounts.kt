package com.letta.mobile.ui.canvas.mount

import com.letta.mobile.data.canvas.mount.client.CanvasMountChange
import com.letta.mobile.data.canvas.mount.client.CanvasMountClient
import com.letta.mobile.data.canvas.mount.client.CanvasMountLookup
import com.letta.mobile.data.canvas.mount.client.CanvasMountProblem
import com.letta.mobile.data.canvas.mount.client.CanvasMountSource
import com.letta.mobile.data.canvas.mount.client.ConversationCanvasTarget
import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** A host's mounts in memory for UI tests; [refusal] answers the next writes instead; [support] flips the host's offer. */
internal class TestHostMounts(supported: Boolean = true) : CanvasMountClient {
    val support = MutableStateFlow(supported)
    override val supported: StateFlow<Boolean> get() = support
    val stored = mutableMapOf<ConversationCanvasTarget, CanvasId>()
    var refusal: CanvasMountProblem? = null

    override suspend fun resolve(target: ConversationCanvasTarget): CanvasMountLookup =
        stored[target]?.let { CanvasMountLookup.Found(it, CanvasMountSource.Binding) } ?: CanvasMountLookup.None

    override suspend fun mount(target: ConversationCanvasTarget, canvas: CanvasId): CanvasMountChange = write(target, canvas)

    override suspend fun unmount(target: ConversationCanvasTarget): CanvasMountChange = write(target, target.ownCanvas)

    override fun changes(target: ConversationCanvasTarget): Flow<CanvasId> = emptyFlow()

    private fun write(target: ConversationCanvasTarget, canvas: CanvasId): CanvasMountChange {
        refusal?.let { return CanvasMountChange.Failed(it) }
        stored[target] = canvas
        return CanvasMountChange.Applied(canvas)
    }
}
