package com.letta.mobile.ui.screens.canvas

import com.letta.mobile.data.canvas.library.CanvasBoardOwner
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.canvas.mount.client.CanvasMountClient
import com.letta.mobile.data.canvas.mount.client.DeviceFallbackCanvasMountClient
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import javax.inject.Provider

/**
 * Binds shared board ownership to the Android retained-window lifecycle, and holds the window's
 * canvas mounts for as long: the host's admin RPC client is not wired yet, so a pick is mounted on
 * this device only and survives a configuration change.
 */
@ActivityRetainedScoped
class AndroidCanvasBoards @Inject constructor(
    factory: Provider<CanvasBoardSessions>,
    lifecycle: ActivityRetainedLifecycle,
) : CanvasBoardOwner(factory::get) {
    private var held: Pair<Long, CanvasMountClient>? = null

    init { lifecycle.addOnClearedListener(::close) }

    /**
     * The window's canvas mounts for the boards of [generation] ([context]'s): the same ones across a
     * configuration change, and none carried over to a new backend, whose conversations may share
     * ids with the last one's but not its canvases.
     */
    fun mounts(generation: Long): CanvasMountClient =
        held?.takeIf { it.first == generation }?.second
            ?: DeviceFallbackCanvasMountClient(CanvasMountClient.Unsupported).also { held = generation to it }
}
