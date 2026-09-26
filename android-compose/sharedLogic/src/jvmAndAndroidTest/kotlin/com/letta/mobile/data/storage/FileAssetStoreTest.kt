package com.letta.mobile.data.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileAssetStoreTest {
    private val root: File = Files.createTempDirectory("asset-store-test").toFile()
    private val directory = File(root, "assets")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun anyKindOfBytesRoundTripsByTheirHash() {
        val store = FileAssetStore(directory)
        val pdf = "%PDF-1.7 not really".encodeToByteArray()
        val asset = store.put("application/pdf", pdf)

        assertTrue(AssetRefs.isValid(asset.ref), asset.ref)
        assertEquals("application/pdf", asset.mediaType)
        assertEquals(pdf.size.toLong(), asset.byteSize)
        assertTrue(store.has(asset.ref))
        assertContentEquals(pdf, store.get(asset.ref))
        assertEquals("application/pdf", store.mediaType(asset.ref))
    }

    @Test
    fun theSameBytesAreOneAsset() {
        val store = FileAssetStore(directory)
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val first = store.put("image/png", bytes)
        val second = store.put("image/png", bytes)
        assertEquals(first.ref, second.ref)
        assertEquals(2, directory.listFiles()!!.size, "one asset and its type, whatever the number of puts")
    }

    @Test
    fun aFileThatNoLongerMatchesItsHashReadsAsMissing() {
        val store = FileAssetStore(directory)
        val asset = store.put("text/plain", "original".encodeToByteArray())
        File(directory, AssetRefs.hashOf(asset.ref)!!).writeText("tampered")
        assertNull(store.get(asset.ref))
    }

    @Test
    fun malformedRefsNeverReachTheFilesystem() {
        val store = FileAssetStore(directory)
        store.put("text/plain", "x".encodeToByteArray())
        for (ref in listOf("sha256:../../etc/passwd", "sha256:ABC", "md5:" + "a".repeat(32), "")) {
            assertFalse(store.has(ref), ref)
            assertNull(store.get(ref), ref)
            assertNull(store.mediaType(ref), ref)
        }
    }

    @Test
    fun anAssetOverTheLimitIsRefused() {
        val store = FileAssetStore(directory, maxAssetBytes = 16)
        assertFailsWith<IllegalArgumentException> { store.put("application/octet-stream", ByteArray(17)) }
    }

    @Test
    fun aSecondStoreOnTheSameDirectorySeesTheAssets() {
        val asset = FileAssetStore(directory).put("image/jpeg", byteArrayOf(1, 2, 3))
        val reopened = FileAssetStore(directory)
        assertContentEquals(byteArrayOf(1, 2, 3), reopened.get(asset.ref))
        assertEquals("image/jpeg", reopened.mediaType(asset.ref))
    }

    @Test
    fun theInMemoryStoreBehavesTheSame() {
        val store = InMemoryAssetStore()
        val bytes = byteArrayOf(9, 8, 7)
        val asset = store.put("audio/ogg", bytes)
        assertEquals(asset.ref, store.put("audio/ogg", bytes).ref)
        assertContentEquals(bytes, store.get(asset.ref))
        assertEquals("audio/ogg", store.mediaType(asset.ref))
        assertFalse(store.has(AssetRefs.PREFIX + "0".repeat(64)))
    }
}
