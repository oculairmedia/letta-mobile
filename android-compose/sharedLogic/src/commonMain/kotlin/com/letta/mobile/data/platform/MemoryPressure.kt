package com.letta.mobile.data.platform

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/** How hard the host is pressing on resident memory. */
enum class MemoryPressureLevel {
    MODERATE,
    CRITICAL,
}

/**
 * One signal, many owners. Hosts only [emit]; every cache that can shrink
 * collects [levels] on the thread that owns it, so a host thread never reaches into UI-thread state.
 */
class MemoryPressureHub {
    private val signals = MutableSharedFlow<MemoryPressureLevel>(
        extraBufferCapacity = SIGNAL_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val levels: SharedFlow<MemoryPressureLevel> = signals.asSharedFlow()

    /** Never suspends: hosts call this from OS callbacks and JMX notification threads. */
    fun emit(level: MemoryPressureLevel) {
        signals.tryEmit(level)
    }

    /** One tick per CRITICAL signal: the cue to drop resident pages. */
    fun criticalTrims(): Flow<Unit> = levels.filter { it == MemoryPressureLevel.CRITICAL }.map { }

    private companion object {
        const val SIGNAL_BUFFER = 8
    }
}

/** The process's hub. Hosts emit into it; shared consumers collect from it. */
object MemoryPressure {
    private val hub = MemoryPressureHub()

    val levels: SharedFlow<MemoryPressureLevel> get() = hub.levels

    fun emit(level: MemoryPressureLevel) = hub.emit(level)

    fun criticalTrims(): Flow<Unit> = hub.criticalTrims()
}
