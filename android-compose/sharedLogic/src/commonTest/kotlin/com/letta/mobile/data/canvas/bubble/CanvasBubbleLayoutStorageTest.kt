package com.letta.mobile.data.canvas.bubble

import com.letta.mobile.data.storage.InMemorySecureSettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals

class CanvasBubbleLayoutStorageTest {
    @Test
    fun nothingStoredReadsAsThePlatformDefaults() {
        assertEquals(CanvasBubbleLayout.Desktop, CanvasBubbleLayoutCodec.read(InMemorySecureSettingsStore(), CanvasBubbleLayout.Desktop))
    }

    @Test
    fun aLayoutRoundTripsAndAnUnsetWidthIsRemoved() {
        val settings = InMemorySecureSettingsStore()
        val layout = CanvasBubbleLayout.Desktop.copy(mascotSizeDp = 120f)
        CanvasBubbleLayoutCodec.write(settings, layout)
        assertEquals(layout, CanvasBubbleLayoutCodec.read(settings, CanvasBubbleLayout.Desktop))

        CanvasBubbleLayoutCodec.write(settings, layout.copy(composerWidthDp = null))

        assertEquals(null, settings.getString(CanvasBubbleLayoutCodec.KEY_COMPOSER_WIDTH))
    }
}
