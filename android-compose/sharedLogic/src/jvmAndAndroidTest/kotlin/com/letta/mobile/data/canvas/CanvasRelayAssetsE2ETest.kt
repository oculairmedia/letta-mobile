package com.letta.mobile.data.canvas

import com.letta.mobile.data.storage.InMemoryAssetStore
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An image put on a board on one app reaches another app through the host (w3nb2.3). */
class CanvasRelayAssetsE2ETest {
    private fun imageOp(opId: String, lamport: Long, elementId: String, ref: String) = CanvasOp.AddElementOp(
        opId, CanvasSession.LOCAL_USER_ACTOR_ID, lamport, elementId, """{"id":"$elementId","type":"Image","imageRef":"$ref"}""",
    )

    @Test
    fun theAssetGoesUpBeforeTheOpThatRefersToItAndAnotherAppFetchesIt() = runTest {
        val hostAssets = InMemoryAssetStore()
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host-1" }, assets = hostAssets)
        val phoneAssets = InMemoryAssetStore()
        val desktopAssets = InMemoryAssetStore()
        val phone = TestApp("phone", backgroundScope, assets = phoneAssets).open("conv-1")
        val desktop = TestApp("desktop", backgroundScope, assets = desktopAssets).open("conv-1")
        val phoneLink = phone.connect(host)
        desktop.connect(host)
        runCurrent()

        val bytes = ByteArray(2 * CanvasRelayHost.ASSET_CHUNK_BYTES + 5) { (it % 251).toByte() }
        val asset = phoneAssets.put("image/png", bytes)
        phone.edit(imageOp("op-img", 3, "img-1", asset.ref))
        runCurrent()

        val sent = phoneLink.sent
        val lastPut = sent.indexOfLast { it is CanvasRelayMessage.AssetPut }
        val publish = sent.indexOfFirst { it is CanvasRelayMessage.Publish && it.op.opId == "op-img" }
        assertTrue(lastPut in 0 until publish, "every chunk goes up before the op: puts end at $lastPut, publish at $publish")
        assertContentEquals(bytes, hostAssets.get(asset.ref))

        val fetched = async { desktop.client.fetchAsset(desktop.canvasId, asset.ref) }
        runCurrent()
        assertContentEquals(bytes, fetched.await())
        assertTrue(desktopAssets.has(asset.ref), "kept, so it is fetched once")

        // A second op on the same image does not send the bytes again on this connection.
        val putsBefore = phoneLink.sent.count { it is CanvasRelayMessage.AssetPut }
        phone.edit(imageOp("op-img-2", 4, "img-2", asset.ref))
        runCurrent()
        assertEquals(putsBefore, phoneLink.sent.count { it is CanvasRelayMessage.AssetPut })
    }

    @Test
    fun anAssetTheHostDoesNotHaveIsNullNotAHang() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host-1" }, assets = InMemoryAssetStore())
        val desktop = TestApp("desktop", backgroundScope, assets = InMemoryAssetStore()).open("conv-1")
        desktop.connect(host)
        runCurrent()

        val nobodyHasIt = "sha256:" + "a".repeat(64)
        val fetched = async { desktop.client.fetchAsset(desktop.canvasId, nobodyHasIt) }
        runCurrent()
        assertNull(fetched.await())
    }

    @Test
    fun aFetchInFlightWhenTheConnectionDropsEndsInsteadOfWaiting() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host-1" }, assets = InMemoryAssetStore())
        val phone = TestApp("phone", backgroundScope, assets = InMemoryAssetStore()).open("conv-1")
        val desktop = TestApp("desktop", backgroundScope, assets = InMemoryAssetStore()).open("conv-1")
        val phoneLink = phone.connect(host)
        val desktopLink = desktop.connect(host)
        runCurrent()

        // The phone starts sending an asset but never finishes; the desktop asks for it and waits.
        val bytes = ByteArray(3000) { 5 }
        val asset = InMemoryAssetStore().put("image/png", bytes)
        phoneLink.send(CanvasRelayMessage.AssetPut(CanvasRelayProtocol.conversationTopic("conv-1"), asset, 0, 2, kotlin.io.encoding.Base64.encode(bytes, 0, 1500)))
        val fetched = async { desktop.client.fetchAsset(desktop.canvasId, asset.ref) }
        runCurrent()
        desktopLink.drop()
        runCurrent()
        assertNull(fetched.await())
    }

    @Test
    fun refsAreFoundWhereverAnOpCarriesThem() {
        val a = "sha256:" + "1".repeat(64)
        val b = "sha256:" + "2".repeat(64)
        val batch = CanvasOp.BatchOp(
            "batch", "local-user", 1,
            listOf(imageOp("x", 1, "e1", a), CanvasOp.SetDocumentOp("d", "local-user", 1, "doc", """{"cover":"$b"}""")),
        )
        assertEquals(setOf(a, b), CanvasAssetRefs.of(batch))
        assertTrue(CanvasAssetRefs.of(imageOp("y", 1, "e2", "sha256:short")).isEmpty())
    }
}
