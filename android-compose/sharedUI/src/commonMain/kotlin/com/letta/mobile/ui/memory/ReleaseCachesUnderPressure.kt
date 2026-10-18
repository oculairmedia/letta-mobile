package com.letta.mobile.ui.memory

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.memory.MemoryCache
import com.letta.mobile.data.platform.MemoryPressure
import com.letta.mobile.data.platform.MemoryPressureLevel
import com.letta.mobile.ui.chat.surface.ChatImageBitmaps
import io.ak1.drawbox.DrawboxCachePressure

/**
 * Shrinks the shell's decoded-image caches when the host reports memory pressure. Collected in
 * composition, so the UI-thread-only caches are only ever touched from the UI thread.
 */
@Composable
fun ReleaseCachesUnderPressure() {
    val context = LocalPlatformContext.current
    LaunchedEffect(context) {
        MemoryPressure.levels.collect { level ->
            UiCachePressure.release(level, SingletonImageLoader.get(context).memoryCache)
        }
    }
}

internal object UiCachePressure {
    /** MODERATE halves what is held; CRITICAL empties the caches (rows on screen keep their image). */
    fun release(level: MemoryPressureLevel, images: MemoryCache?) {
        when (level) {
            MemoryPressureLevel.MODERATE -> {
                ChatImageBitmaps.trim()
                images?.let { it.trimToSize(it.maxSize / 2) }
            }
            MemoryPressureLevel.CRITICAL -> {
                ChatImageBitmaps.clear()
                images?.clear()
                DrawboxCachePressure.dropIdle()
            }
        }
    }
}
