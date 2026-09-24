package com.letta.mobile.data.canvas

import com.letta.mobile.data.storage.AssetRef
import com.letta.mobile.data.storage.InMemoryAssetStore
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The host keeps the assets apps put beside their ops and serves them to the others (w3nb2.3). */
class CanvasRelayHostAssetsTest {
    private val topic = "conversation:c1"

    /** The ref its bytes really have, via a store (so the test states nothing it could get wrong). */
    private fun assetOf(bytes: ByteArray, mediaType: String = "image/png"): AssetRef = InMemoryAssetStore().put(mediaType, bytes)

    private fun chunks(asset: AssetRef, bytes: ByteArray, size: Int): List<CanvasRelayMessage.AssetPut> {
        val count = (bytes.size + size - 1) / size
        return (0 until count).map { i ->
            val from = i * size
            CanvasRelayMessage.AssetPut(topic, asset, i, count, Base64.encode(bytes, from, minOf(bytes.size, from + size)))
        }
    }

    private fun reassemble(data: List<CanvasRelayMessage.AssetData>): ByteArray =
        data.sortedBy { it.index }.map { Base64.decode(it.data) }.reduce { a, b -> a + b }

    private suspend fun joined(host: CanvasRelayHost, origin: String) =
        RecordingApp(origin).connect(host).also { it.send(CanvasRelayMessage.Join(topic, "canvas-1")) }

    @Test
    fun anAssetPutInChunksIsStoredVerifiedAndServedToAnotherApp() = runTest {
        val store = InMemoryAssetStore()
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host" }, assets = store)
        val phone = joined(host, "phone")
        val desktop = joined(host, "desktop")
        val bytes = ByteArray(3 * CanvasRelayHost.ASSET_CHUNK_BYTES + 17) { (it % 253).toByte() }
        val asset = assetOf(bytes)

        chunks(asset, bytes, CanvasRelayHost.ASSET_CHUNK_BYTES).forEach { phone.send(it) }
        assertEquals(listOf(asset.ref), phone.of<CanvasRelayMessage.AssetStored>().map { it.ref })
        assertContentEquals(bytes, store.get(asset.ref))

        desktop.send(CanvasRelayMessage.AssetGet(topic, asset.ref))
        val data = desktop.of<CanvasRelayMessage.AssetData>()
        assertEquals(4, data.size, "in chunks that fit a frame")
        assertTrue(data.all { it.asset.ref == asset.ref && it.asset.mediaType == "image/png" })
        assertContentEquals(bytes, reassemble(data))
    }

    @Test
    fun bytesThatAreNotTheAssetTheyClaimAreRefused() = runTest {
        val store = InMemoryAssetStore()
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host" }, assets = store)
        val phone = joined(host, "phone")
        val claimed = assetOf("the real picture".encodeToByteArray())
        val forged = "something else!!".encodeToByteArray()

        phone.send(CanvasRelayMessage.AssetPut(topic, claimed.copy(byteSize = forged.size.toLong()), 0, 1, Base64.encode(forged)))
        assertEquals("bytes are not that asset", phone.of<CanvasRelayMessage.AssetRejected>().single().reason)
        assertTrue(!store.has(claimed.ref), "nothing is served under a ref its bytes do not have")
    }

    @Test
    fun anAssetTheHostAlreadyHoldsIsNotTakenAgain() = runTest {
        val store = InMemoryAssetStore()
        val bytes = ByteArray(2 * 1024) { 7 }
        val asset = store.put("image/jpeg", bytes)
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host" }, assets = store)
        val phone = joined(host, "phone")

        chunks(asset, bytes, 1024).forEach { phone.send(it) }
        assertEquals(1, phone.of<CanvasRelayMessage.AssetStored>().size, "said once, at the first chunk")
    }

    @Test
    fun aRequestForAnAssetStillArrivingIsAnsweredWhenItLands() = runTest {
        val store = InMemoryAssetStore()
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host" }, assets = store)
        val phone = joined(host, "phone")
        val desktop = joined(host, "desktop")
        val bytes = ByteArray(2 * 1000) { (it % 97).toByte() }
        val asset = assetOf(bytes)
        val parts = chunks(asset, bytes, 1000)

        phone.send(parts[0])
        desktop.send(CanvasRelayMessage.AssetGet(topic, asset.ref))
        assertTrue(desktop.of<CanvasRelayMessage.AssetData>().isEmpty() && desktop.of<CanvasRelayMessage.AssetMissing>().isEmpty(), "it waits")
        phone.send(parts[1])
        assertContentEquals(bytes, reassemble(desktop.of<CanvasRelayMessage.AssetData>()))
    }

    @Test
    fun aRequestWaitingOnAnAppThatLeftIsToldTheAssetIsMissing() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host" }, assets = InMemoryAssetStore())
        val phone = joined(host, "phone")
        val desktop = joined(host, "desktop")
        val bytes = ByteArray(2 * 1000) { 3 }
        val asset = assetOf(bytes)

        phone.send(chunks(asset, bytes, 1000).first())
        desktop.send(CanvasRelayMessage.AssetGet(topic, asset.ref))
        phone.session.close()
        assertEquals(listOf(asset.ref), desktop.of<CanvasRelayMessage.AssetMissing>().map { it.ref })
    }

    @Test
    fun onlyAnAppOnTheTopicPutsOrGetsAndAHostWithoutAStoreRefuses() = runTest {
        val store = InMemoryAssetStore()
        val bytes = byteArrayOf(1, 2, 3)
        val asset = store.put("image/png", bytes)
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host" }, assets = store)
        val stranger = RecordingApp("stranger").connect(host)

        stranger.send(CanvasRelayMessage.AssetGet(topic, asset.ref))
        assertEquals(listOf(asset.ref), stranger.of<CanvasRelayMessage.AssetMissing>().map { it.ref })
        stranger.send(CanvasRelayMessage.AssetPut(topic, asset, 0, 1, Base64.encode(bytes)))
        assertEquals("not joined", stranger.of<CanvasRelayMessage.AssetRejected>().single().reason)

        val opsOnly = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host" })
        val phone = joined(opsOnly, "phone")
        phone.send(CanvasRelayMessage.AssetPut(topic, asset, 0, 1, Base64.encode(bytes)))
        assertEquals("this host keeps no assets", phone.of<CanvasRelayMessage.AssetRejected>().single().reason)
    }

    @Test
    fun anAssetLargerThanTheHostKeepsIsRefused() = runTest {
        val host = CanvasRelayHost(InMemoryCanvasRelayStore(), hostId = { "host" }, assets = InMemoryAssetStore(), maxAssetBytes = 1024)
        val phone = joined(host, "phone")
        val big = assetOf(ByteArray(2048) { 1 })
        phone.send(CanvasRelayMessage.AssetPut(topic, big, 0, 1, Base64.encode(ByteArray(2048) { 1 })))
        assertEquals("larger than this host keeps", phone.of<CanvasRelayMessage.AssetRejected>().single().reason)
    }

    @Test
    fun assetMessagesSurviveTheWire() {
        val asset = assetOf(byteArrayOf(9, 9, 9))
        for (message in listOf(
            CanvasRelayMessage.AssetPut(topic, asset, 0, 1, "CQkJ"),
            CanvasRelayMessage.AssetGet(topic, asset.ref),
            CanvasRelayMessage.AssetStored(topic, asset.ref),
            CanvasRelayMessage.AssetData(topic, asset, 0, 1, "CQkJ"),
            CanvasRelayMessage.AssetMissing(topic, asset.ref),
            CanvasRelayMessage.AssetRejected(topic, asset.ref, "why"),
        )) {
            val decoded = CanvasRelayProtocol.decode(CanvasRelayProtocol.encode(message))
            assertEquals(CanvasRelayDecoded.Message(message), decoded)
        }
    }
}
