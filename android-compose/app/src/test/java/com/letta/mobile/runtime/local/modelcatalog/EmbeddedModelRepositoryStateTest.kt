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
}
