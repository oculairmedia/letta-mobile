package com.letta.mobile.ui.screens.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class EmbeddedModelDownloadProgressTest {
    @Test
    fun progressLabel_includesDownloadedTotalAndPercentWhenTotalKnown() {
        val downloaded = 384L * 1024L * 1024L
        val total = 3L * 1024L * 1024L * 1024L

        val label = embeddedModelDownloadProgressLabel(downloaded, total)

        assertEquals("384 MiB / 3.0 GiB · 13%", label)
    }

    @Test
    fun progressLabel_includesOnlyDownloadedBytesWhenTotalUnknown() {
        val downloaded = 384L * 1024L * 1024L

        val label = embeddedModelDownloadProgressLabel(downloaded, null)

        assertEquals("384 MiB", label)
    }

    @Test
    fun progress_isNullWhenTotalUnknownAndClampedWhenKnown() {
        assertNull(embeddedModelDownloadProgress(bytesDownloaded = 384L, totalBytes = null))
        assertEquals(0f, embeddedModelDownloadProgress(bytesDownloaded = -1L, totalBytes = 100L))
        assertEquals(1f, embeddedModelDownloadProgress(bytesDownloaded = 101L, totalBytes = 100L))
        assertEquals(0.25f, embeddedModelDownloadProgress(bytesDownloaded = 25L, totalBytes = 100L))
    }
}
