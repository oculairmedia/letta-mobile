package com.letta.mobile.desktop.chat

import com.letta.mobile.runtime.RuntimeEventPayload
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** One runtime event, scoped to the conversation and agent it belongs to. */
data class ScopedDesktopRuntimeEvent(
    val conversationId: String,
    val agentId: String,
    val payload: RuntimeEventPayload,
)

/**
 * A gateway that can report the runtime events of the turns it drives.
 *
 * Desktop's chat controller folds these through the shared run-phase reducer, which is how the
 * mascot learns that the agent is running a tool rather than merely "busy". Gateways that cannot
 * report events (demo / HTTP-only) simply don't implement it; the controller still publishes the
 * run lifecycle it knows first-hand.
 */
interface DesktopRuntimeEventSource {
    val runtimeEvents: SharedFlow<ScopedDesktopRuntimeEvent>
}

/**
 * The emitter side. Buffered but lossless: the reducer folds every event, so dropping one (a tool
 * return, a terminal) would leave a phase stuck until a later event happened to repair it. A
 * collector that falls more than [BUFFER_CAPACITY] events behind back-pressures the turn's
 * collector briefly instead - the emitting call sites are already inside suspending collects.
 */
class DesktopRuntimeEventRelay : DesktopRuntimeEventSource {
    private val _events = MutableSharedFlow<ScopedDesktopRuntimeEvent>(extraBufferCapacity = BUFFER_CAPACITY)
    override val runtimeEvents: SharedFlow<ScopedDesktopRuntimeEvent> = _events.asSharedFlow()

    suspend fun emit(conversationId: String, agentId: String, payload: RuntimeEventPayload) {
        _events.emit(ScopedDesktopRuntimeEvent(conversationId, agentId, payload))
    }

    private companion object {
        const val BUFFER_CAPACITY = 128
    }
}
