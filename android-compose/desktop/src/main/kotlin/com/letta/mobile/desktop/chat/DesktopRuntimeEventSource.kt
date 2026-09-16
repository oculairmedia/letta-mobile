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
 * The emitter side. Bounded and non-suspending on purpose: presence is a live signal, so a slow
 * collector drops the oldest events rather than back-pressuring the turn that produced them.
 */
class DesktopRuntimeEventRelay : DesktopRuntimeEventSource {
    private val _events = MutableSharedFlow<ScopedDesktopRuntimeEvent>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    override val runtimeEvents: SharedFlow<ScopedDesktopRuntimeEvent> = _events.asSharedFlow()

    fun emit(conversationId: String, agentId: String, payload: RuntimeEventPayload) {
        _events.tryEmit(ScopedDesktopRuntimeEvent(conversationId, agentId, payload))
    }

    private companion object {
        const val BUFFER_CAPACITY = 128
    }
}
