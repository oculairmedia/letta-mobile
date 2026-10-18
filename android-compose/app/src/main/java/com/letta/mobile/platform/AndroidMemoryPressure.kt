package com.letta.mobile.platform

import android.content.ComponentCallbacks2
import com.letta.mobile.data.platform.MemoryPressureLevel

/**
 * Maps Android [ComponentCallbacks2] trim levels to the shared signal. Only levels that report the
 * device actually running low count: UI_HIDDEN and BACKGROUND arrive on every trip to the
 * background, and Coil already trims its own cache for those through its system callbacks.
 */
object AndroidMemoryPressure {
    fun levelFor(trimLevel: Int): MemoryPressureLevel? = when (trimLevel) {
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
        -> MemoryPressureLevel.CRITICAL
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
        ComponentCallbacks2.TRIM_MEMORY_MODERATE,
        -> MemoryPressureLevel.MODERATE
        else -> null
    }
}
