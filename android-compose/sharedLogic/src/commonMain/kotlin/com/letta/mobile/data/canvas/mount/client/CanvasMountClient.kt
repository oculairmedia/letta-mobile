package com.letta.mobile.data.canvas.mount.client

import com.letta.mobile.data.model.CanvasId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The app's view of the host's canvas mounts: which canvas a conversation shows, and the person's
 * switch to another. The host owns the mount, so every paired device and the agent's canvas tools
 * see the same board. Callers go through [ConversationCanvasResolver], never this port directly.
 *
 * Which canvas a conversation shows is decided in one order: the conversation's own choice (its
 * binding, which may name its own canvas), else its agent's default canvas, else the conversation's
 * own board.
 *
 * An implementation over the host's admin RPC must:
 * - publish [supported] true only while the connected host advertises the `canvas.mount.v1`
 *   capability, and false again when the connection changes;
 * - map [resolve] to `canvas.mount.resolve {agent_id, conversation_id}`: `found` to
 *   [CanvasMountLookup.Found] (source `binding` or `default`, its durability), `none` to
 *   [CanvasMountLookup.None];
 * - map [mount] to `canvas.mount.binding.replace {agent_id, conversation_id, canvas_id}` and
 *   `replaced` to [CanvasMountChange.Applied];
 * - map [unmount] to a `canvas.mount.binding.replace` with the conversation's own canvas
 *   ([ConversationCanvasTarget.ownCanvas]), never to `canvas.mount.binding.clear`: choosing the own
 *   board is the conversation's own choice and wins over its agent's default, which a clear would
 *   fall through to;
 * - map the refusals `canvas.mount.denied` to [CanvasMountProblem.Denied] and
 *   `canvas.mount.invalid_input:<field>:<code>` to [CanvasMountProblem.InvalidInput], the answers
 *   `unavailable{reason}` to [CanvasMountProblem.Unavailable] and `outcome_unknown` (or a lost
 *   connection after the request was sent) to [CanvasMountProblem.OutcomeUnknown];
 * - feed [changes] from the host's change notification when it has one, else return an empty flow:
 *   each emission is the board the conversation shows after the change, its own canvas
 *   ([ConversationCanvasTarget.ownCanvas]) once nothing is mounted for it;
 * - keep the default [reach]: a host's mount reaches every paired device and the agent's tools.
 * It must never throw for a host answer; cancellation propagates.
 *
 * [DeviceCanvasMountClient] is the device-local implementation, and [DeviceFallbackCanvasMountClient]
 * the one place that picks between the host's client and it.
 */
interface CanvasMountClient {
    /** True while the connected host offers canvas mounts; while false nothing is asked. */
    val supported: StateFlow<Boolean>

    /** Which canvas the host has mounted for [target]. */
    suspend fun resolve(target: ConversationCanvasTarget): CanvasMountLookup

    /**
     * [resolve], answered only by mounts that reach the host (every paired device and the agent's
     * tools). Mounts held on this device answer [CanvasMountUnavailableReason.DeviceOnly] instead, so
     * the agent's tools and the confirmation of a host write never take a device-only answer.
     */
    suspend fun resolveOnHost(target: ConversationCanvasTarget): CanvasMountLookup = resolve(target)

    /** Mounts [canvas] for [target] only, replacing whatever it showed. */
    suspend fun mount(target: ConversationCanvasTarget, canvas: CanvasId): CanvasMountChange

    /** Chooses [target]'s own board for it, explicitly, so it wins over its agent's default; never a clear. */
    suspend fun unmount(target: ConversationCanvasTarget): CanvasMountChange

    /** The board [target] shows after each change the host pushes after this call (its own canvas once unmounted); empty when the host pushes none. */
    fun changes(target: ConversationCanvasTarget): Flow<CanvasId>

    /** Who the mounts this client reports reach: the host (every device and the agent's tools) or this device only. */
    val reach: Flow<CanvasMountReach> get() = flowOf(CanvasMountReach.Host)

    /** A host without canvas mounts: never supported, nothing is mounted and nothing changes. */
    object Unsupported : CanvasMountClient {
        private val never = MutableStateFlow(false).asStateFlow()
        private val refused = CanvasMountChange.Failed(CanvasMountProblem.Unavailable(CanvasMountUnavailableReason.Unsupported))

        override val supported: StateFlow<Boolean> get() = never

        override suspend fun resolve(target: ConversationCanvasTarget): CanvasMountLookup = CanvasMountLookup.None

        override suspend fun mount(target: ConversationCanvasTarget, canvas: CanvasId): CanvasMountChange = refused

        override suspend fun unmount(target: ConversationCanvasTarget): CanvasMountChange = refused

        override fun changes(target: ConversationCanvasTarget): Flow<CanvasId> = emptyFlow()
    }
}
