package com.letta.mobile.data.canvas

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a canvas is actually shared, as its transport knows it. Shown in the canvas chrome, so a
 * board that is not syncing says so instead of looking like one that is.
 */
sealed interface CanvasSyncHealth {
    /** Every local edit acknowledged by the host, and caught up to the host's cursor. */
    data object Synced : CanvasSyncHealth

    /** Reaching the host, uploading queued edits or catching up; not yet [Synced]. */
    data object Connecting : CanvasSyncHealth

    /** The host is out of reach; [queued] local edits wait, durably, to be uploaded. */
    data class OfflineQueued(val queued: Int) : CanvasSyncHealth

    /** Nothing leaves this app: no host to share through ([reason] says why). */
    data class LocalOnly(val reason: String) : CanvasSyncHealth

    /** The host refused this canvas or spoke a protocol this app cannot ([reason]). */
    data class Failed(val reason: String) : CanvasSyncHealth
}

/**
 * The health of a transport that never leaves this process. It can only ever say [CanvasSyncHealth.LocalOnly]:
 * a loopback that reported anything else would be the silent fallback this model exists to end.
 */
class LocalOnlyCanvasSyncHealth(reason: String) {
    private val state = MutableStateFlow<CanvasSyncHealth>(CanvasSyncHealth.LocalOnly(reason))
    val health: StateFlow<CanvasSyncHealth> = state.asStateFlow()

    /** Only [CanvasSyncHealth.LocalOnly] or [CanvasSyncHealth.Failed]; anything claiming to share throws. */
    fun report(health: CanvasSyncHealth) {
        check(health is CanvasSyncHealth.LocalOnly || health is CanvasSyncHealth.Failed) {
            "A local-only canvas transport cannot report $health"
        }
        state.value = health
    }
}
