package com.letta.mobile.data.canvas

import com.letta.mobile.data.plugin.catalog.pluginPackageSha256
import com.letta.mobile.data.plugin.remote.RelayPluginPackageUploader
import com.letta.mobile.data.storage.InMemoryAssetStore
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * letta-mobile-cdgpr.8: a plugin package goes into the host's asset store over the canvas relay,
 * outside any canvas, pinned by its sha256, so `plugins.install` can install it from there.
 */
class RelayPluginPackageUploaderTest {
    private val pkg = ByteArray(2 * CanvasRelayHost.ASSET_CHUNK_BYTES + 5) { (it % 251).toByte() }
    private val sha = pluginPackageSha256(pkg)

    @Test
    fun aPackageLandsInTheHostsStoreWithoutJoiningACanvas() = runTest {
        val hostAssets = InMemoryAssetStore()
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host-1" }, assets = hostAssets)
        val app = TestApp("phone", backgroundScope)
        val link = app.connect(host)
        runCurrent()

        val ref = async { RelayPluginPackageUploader(app.client).upload(pkg, sha) }
        runCurrent()

        assertEquals("sha256:$sha", ref.await())
        assertContentEquals(pkg, hostAssets.get("sha256:$sha"))
        assertEquals(RelayPluginPackageUploader.PLUGIN_PACKAGE_MEDIA_TYPE, hostAssets.mediaType("sha256:$sha"))
        assertFalse(link.sent.any { it is CanvasRelayMessage.Join }, "no canvas was joined for the upload")
        assertTrue(link.sent.filterIsInstance<CanvasRelayMessage.AssetPut>().all { it.topic == CanvasRelayProtocol.UPLOAD_TOPIC })

        // The same package again: the host already holds it and says so.
        val again = async { RelayPluginPackageUploader(app.client).upload(pkg, sha) }
        runCurrent()
        assertEquals("sha256:$sha", again.await())
    }

    @Test
    fun bytesThatDoNotMatchTheShownHashAreRefusedBeforeAnythingIsSent() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host-1" }, assets = InMemoryAssetStore())
        val app = TestApp("phone", backgroundScope)
        val link = app.connect(host)
        runCurrent()

        val refused = assertFailsWith<CanvasRelayUploadException> { RelayPluginPackageUploader(app.client).upload(pkg, "0".repeat(64)) }

        assertTrue(refused.message.orEmpty().contains("do not match"), refused.message)
        assertFalse(link.sent.any { it is CanvasRelayMessage.AssetPut })
    }

    @Test
    fun aHostThatKeepsNoAssetsRefusesWithItsReason() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host-1" }, assets = null)
        val app = TestApp("phone", backgroundScope)
        app.connect(host)
        runCurrent()

        val upload = async { runCatching { RelayPluginPackageUploader(app.client).upload(pkg, sha) } }
        runCurrent()

        val failure = upload.await().exceptionOrNull()
        assertTrue(failure is CanvasRelayUploadException && failure.message.orEmpty().contains("this host keeps no assets"), "$failure")
    }

    @Test
    fun withoutAConnectionTheUploadFailsAtOnce() = runTest {
        val app = TestApp("phone", backgroundScope)

        assertFailsWith<CanvasRelayUploadException> { RelayPluginPackageUploader(app.client).upload(pkg, sha) }
    }

    @Test
    fun aConnectionThatDropsMidUploadEndsItInsteadOfWaiting() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host-1" }, assets = InMemoryAssetStore())
        val app = TestApp("phone", backgroundScope)
        val link = app.connect(host)
        runCurrent()
        link.hold = true

        val upload = async { runCatching { RelayPluginPackageUploader(app.client).upload(pkg, sha) } }
        runCurrent()
        app.disconnect()
        runCurrent()

        val failure = upload.await().exceptionOrNull()
        assertTrue(failure is CanvasRelayUploadException && failure.message.orEmpty().contains(CanvasRelayUploads.CONNECTION_ENDED), "$failure")
    }
}
