package com.letta.mobile.data.canvas.mount.client

import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Canvas mounts held on this device only, in memory for as long as its owner lives (the app
 * session), one per conversation ([ConversationCanvasTarget]). It stands in for a host without
 * mounts so a pick still swaps the board on screen; it reaches nothing else, so its [reach] is
 * [CanvasMountReach.Device]: other devices and the agent's canvas tools keep the conversation's own
 * canvas. It always answers and never fails.
 */
class DeviceCanvasMountClient : CanvasMountClient {
    private val held = MutableStateFlow<Map<ConversationCanvasTarget, CanvasId>>(emptyMap())
    private val always = MutableStateFlow(true).asStateFlow()

    override val supported: StateFlow<Boolean> get() = always

    override val reach: Flow<CanvasMountReach> get() = flowOf(CanvasMountReach.Device)

    override suspend fun resolve(target: ConversationCanvasTarget): CanvasMountLookup =
        held.value[target]?.let { CanvasMountLookup.Found(it, CanvasMountSource.Binding) } ?: CanvasMountLookup.None

    /** Mounting the conversation's own canvas is the same as [unmount]. */
    override suspend fun mount(target: ConversationCanvasTarget, canvas: CanvasId): CanvasMountChange {
        if (canvas == target.ownCanvas) return unmount(target)
        held.update { it + (target to canvas) }
        return CanvasMountChange.Applied(canvas)
    }

    override suspend fun unmount(target: ConversationCanvasTarget): CanvasMountChange {
        held.update { it - target }
        return CanvasMountChange.Applied(target.ownCanvas)
    }

    /** [target]'s board after each change made after this call (its own canvas once unmounted). */
    override fun changes(target: ConversationCanvasTarget): Flow<CanvasId> =
        held.map { it[target] ?: target.ownCanvas }.distinctUntilChanged().drop(1)
}
