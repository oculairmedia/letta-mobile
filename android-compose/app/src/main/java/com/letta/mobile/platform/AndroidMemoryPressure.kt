package com.letta.mobile.platform

import android.content.ComponentCallbacks2
import com.letta.mobile.data.platform.MemoryPressureLevel

/** Maps Android [ComponentCallbacks2] trim levels to the shared signal. */
object AndroidMemoryPressure {
    fun levelFor(trimLevel: Int): MemoryPressureLevel = when (trimLevel) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        -> MemoryPressureLevel.CRITICAL
        else -> MemoryPressureLevel.MODERATE
    }
}
