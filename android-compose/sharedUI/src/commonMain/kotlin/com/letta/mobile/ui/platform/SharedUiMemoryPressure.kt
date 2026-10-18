package com.letta.mobile.ui.platform

import com.letta.mobile.data.platform.MemoryCacheKind
import com.letta.mobile.data.platform.MemoryPressure
import com.letta.mobile.data.platform.MemoryPressureDefaults
import io.ak1.drawbox.DrawboxCachePressure
import kotlinx.atomicfu.atomic

/** Shared UI binds canvas layer caches; hosts still only emit. */
object SharedUiMemoryPressure {
    private val bound = atomic(false)

    fun install() {
        MemoryPressureDefaults.install()
        if (!bound.compareAndSet(false, true)) return
        MemoryPressure.current().caches.bind(MemoryCacheKind.Canvas) {
            DrawboxCachePressure.dropAll()
        }
    }
}
