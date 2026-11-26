package com.letta.mobile.util

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A bounded telemetry history owned by one application or test lifecycle. */
class TelemetryRecorder {
    private val mutableEvents = MutableStateFlow<List<Telemetry.Event>>(emptyList())
    val events: StateFlow<List<Telemetry.Event>> = mutableEvents.asStateFlow()

    /**
     * Also told of every recorded event, on the recording thread: the host's on-disk flight record attaches here. It must
     * return at once (no I/O, no locks held for long), because it runs inside every `Telemetry.event` call.
     */
    @Volatile
    internal var onRecorded: ((Telemetry.Event) -> Unit)? = null

    fun record(event: Telemetry.Event) {
        mutableEvents.update { current -> (listOf(event) + current).take(CAPACITY) }
        onRecorded?.invoke(event)
    }

    fun clear() {
        mutableEvents.value = emptyList()
    }

    fun snapshot(): List<Telemetry.Event> = mutableEvents.value

    private companion object {
        const val CAPACITY = 1000
    }
}
