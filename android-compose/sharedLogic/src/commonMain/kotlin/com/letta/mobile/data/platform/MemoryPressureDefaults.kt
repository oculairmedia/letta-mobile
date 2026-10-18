package com.letta.mobile.data.platform

/** Ensures the shared hub exists before presentations attach or hosts emit. */
object MemoryPressureDefaults {
    fun install() {
        MemoryPressure.current()
    }
}
