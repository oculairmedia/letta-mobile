package com.letta.mobile.runtime.local.modelcatalog

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.letta.mobile.testutil.FakeSettingsRepository
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
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
    fun assetRepositoryDownloadProgress_republishesCatalogWithCoalescedDownloadingState() = runTest {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val totalBytes = 2L * 1024L * 1024L
        server.createContext("/model.litertlm") { exchange ->
            exchange.sendResponseHeaders(200, totalBytes)
            exchange.responseBody.use { output ->
                val chunk = ByteArray(8 * 1024)
                var written = 0L
                while (written < totalBytes) {
                    output.write(chunk)
                    output.flush()
                    written += chunk.size
                }
            }
        }
        server.start()
        try {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val repository = AssetEmbeddedModelRepository(
                context = context,
                settingsRepository = FakeSettingsRepository(),
            )
            repository.refresh()
            val entry = repository.catalog.value.first().entry.copy(
                sizeInBytes = totalBytes,
                downloadUrl = "http://127.0.0.1:${server.address.port}/model.litertlm",
                requiresAuth = false,
            )
            File(File(context.filesDir, "embedded-lettacode/models"), entry.modelFile).delete()

            repository.catalog.test {
                awaitItem()
                val download = launch(Dispatchers.IO) { repository.download(entry) }
                var progress: EmbeddedModelDownloadState.Downloading? = null
                while (progress == null) {
                    val downloading = awaitItem().first().state as? EmbeddedModelDownloadState.Downloading
                    if (downloading != null && downloading.bytesDownloaded > 0L) {
                        progress = downloading
                    }
                }
                val publishedProgress = requireNotNull(progress)
                assertTrue(publishedProgress.bytesDownloaded >= 1024L * 1024L)
                assertEquals(totalBytes, publishedProgress.totalBytes)
                cancelAndIgnoreRemainingEvents()
                download.join()
            }
        } finally {
            server.stop(0)
        }
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
