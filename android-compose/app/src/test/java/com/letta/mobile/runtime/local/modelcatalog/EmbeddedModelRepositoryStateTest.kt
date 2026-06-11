package com.letta.mobile.runtime.local.modelcatalog

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class EmbeddedModelRepositoryStateTest {
    @Test
    fun downloadStates_representProgressCancelFailureAndDownloaded() {
        val progress = EmbeddedModelDownloadState.Downloading(bytesDownloaded = 512L, totalBytes = 1024L)
        val cancelled = EmbeddedModelDownloadState.Cancelled
        val failed = EmbeddedModelDownloadState.Failed("network unavailable")
        val downloaded = EmbeddedModelDownloadState.Downloaded("/data/model.litertlm")

        assertEquals(512L, progress.bytesDownloaded)
        assertEquals(1024L, progress.totalBytes)
        assertEquals(EmbeddedModelDownloadState.Cancelled, cancelled)
        assertEquals("network unavailable", failed.message)
        assertEquals("/data/model.litertlm", downloaded.localPath)
    }

    @Test
    fun shouldPublishEmbeddedModelProgress_coalescesSmallProgressUpdates() {
        assertEquals(true, shouldPublishEmbeddedModelProgress(null, 8L * 1024L, 256L * 1024L * 1024L))
        assertEquals(false, shouldPublishEmbeddedModelProgress(0L, 8L * 1024L, 256L * 1024L * 1024L))
        assertEquals(true, shouldPublishEmbeddedModelProgress(0L, 3L * 1024L * 1024L, 256L * 1024L * 1024L))
        assertEquals(true, shouldPublishEmbeddedModelProgress(3L * 1024L * 1024L, 256L * 1024L * 1024L, 256L * 1024L * 1024L))
    }

    @Test
    fun sanitizeEmbeddedModelDownloadFailure_keepsFailureMessageShortAndSingleLine() {
        val hugeMessage = "first line with safe context" + "x".repeat(500) + "\nsecret-token\nstack trace"

        val sanitized = sanitizeEmbeddedModelDownloadFailure(hugeMessage)

        assertEquals(160, sanitized.length)
        assertEquals(false, sanitized.contains("secret-token"))
        assertEquals("Download failed.", sanitizeEmbeddedModelDownloadFailure(null))
    }
}
