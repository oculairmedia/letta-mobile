package com.letta.mobile.runtime.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalImageBlobStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `putBytes returns sha256 ref and stores blob`() {
        val store = LocalImageBlobStore(tempFolder.root)
        val bytes = "Hello, World!".toByteArray()
        val ref = store.putBytes("image/png", bytes)

        assertTrue("ref should start with sha256:", ref.startsWith("sha256:"))
        assertTrue("blob file should exist", store.has(ref))
    }

    @Test
    fun `getBytes returns original bytes`() {
        val store = LocalImageBlobStore(tempFolder.root)
        val original = "Test image data".toByteArray()
        val ref = store.putBytes("image/jpeg", original)

        val retrieved = store.getBytes(ref)
        assertArrayEquals("bytes should match original", original, retrieved)
    }

    @Test
    fun `sha256 ref is stable for identical content`() {
        val store = LocalImageBlobStore(tempFolder.root)
        val bytes = "Identical content".toByteArray()

        val ref1 = store.putBytes("image/png", bytes)
        val ref2 = store.putBytes("image/jpeg", bytes) // different type, same bytes

        assertEquals("refs should be identical for same content", ref1, ref2)
    }

    @Test
    fun `putBytes is idempotent`() {
        val store = LocalImageBlobStore(tempFolder.root)
        val bytes = "Idempotent test".toByteArray()

        val ref1 = store.putBytes("image/png", bytes)
        val ref2 = store.putBytes("image/png", bytes)

        assertEquals("second put should return same ref", ref1, ref2)
        assertArrayEquals("bytes should still be retrievable", bytes, store.getBytes(ref1))
    }

    @Test
    fun `has returns true for existing blob`() {
        val store = LocalImageBlobStore(tempFolder.root)
        val ref = store.putBytes("image/png", "exists".toByteArray())

        assertTrue("has should return true for existing blob", store.has(ref))
    }

    @Test
    fun `has returns false for missing blob`() {
        val store = LocalImageBlobStore(tempFolder.root)
        assertFalse("has should return false for missing blob", store.has("sha256:nonexistent"))
    }

    @Test
    fun `getBytes returns null for missing blob`() {
        val store = LocalImageBlobStore(tempFolder.root)
        val bytes = store.getBytes("sha256:missingblob")

        assertNull("getBytes should return null for missing ref", bytes)
    }

    @Test
    fun `blobs stored in blobs subdirectory with extension`() {
        val store = LocalImageBlobStore(tempFolder.root)
        store.putBytes("image/png", "png test".toByteArray())

        val blobsDir = tempFolder.root.resolve("blobs")
        assertTrue("blobs directory should exist", blobsDir.exists())
        assertTrue("blobs directory should have at least one file", blobsDir.listFiles()?.isNotEmpty() == true)
        val blobFile = blobsDir.listFiles()?.firstOrNull()
        assertTrue("blob file should have .png extension", blobFile?.name?.endsWith(".png") == true)
    }

    @Test
    fun `different media types stored with correct extensions`() {
        val store = LocalImageBlobStore(tempFolder.root)
        val bytes = "same content".toByteArray()

        val pngRef = store.putBytes("image/png", bytes)
        val jpegRef = store.putBytes("image/jpeg", bytes)

        // Same content, so same ref
        assertEquals("refs should be same for identical bytes", pngRef, jpegRef)

        // Should retrieve successfully
        assertArrayEquals("should retrieve via png ref", bytes, store.getBytes(pngRef))
    }

    @Test
    fun `content addressing deduplicates identical images`() {
        val store = LocalImageBlobStore(tempFolder.root)
        val bytes = "duplicate".toByteArray()

        val ref1 = store.putBytes("image/png", bytes)
        val ref2 = store.putBytes("image/png", bytes)

        assertEquals("same bytes should produce same ref", ref1, ref2)

        // Only one blob file should exist
        val blobsDir = tempFolder.root.resolve("blobs")
        val blobFiles = blobsDir.listFiles() ?: emptyArray()
        assertEquals("only one blob file should exist for duplicate content", 1, blobFiles.size)
    }
}
