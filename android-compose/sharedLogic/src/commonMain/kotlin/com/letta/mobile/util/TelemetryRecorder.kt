package com.letta.mobile.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A bounded telemetry history owned by one application or test lifecycle. */
class TelemetryRecorder {
    private val mutableEvents = MutableStateFlow<List<Telemetry.Event>>(emptyList())
    val events: StateFlow<List<Telemetry.Event>> = mutableEvents.asStateFlow()

    fun record(event: Telemetry.Event) {
        mutableEvents.update { current -> (listOf(event) + current).take(CAPACITY) }
    }

    fun clear() {
        mutableEvents.value = emptyList()
    }

    fun snapshot(): List<Telemetry.Event> = mutableEvents.value

    private companion object {
        const val CAPACITY = 1000
    }
}
