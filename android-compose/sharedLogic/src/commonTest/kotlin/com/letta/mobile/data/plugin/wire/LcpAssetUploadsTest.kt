package com.letta.mobile.data.plugin.wire

import com.letta.mobile.data.canvas.compose.Sha256
import kotlinx.coroutines.test.runTest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Chunked, hash-verified asset uploads (letta-mobile-s416w.25). */
@OptIn(ExperimentalEncodingApi::class)
class LcpAssetUploadsTest {
    private val uploads = LcpAssetUploads()
    private val hello = "hello".encodeToByteArray()
    private val helloSha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    private fun begin(size: Long = hello.size.toLong()): String = uploads.begin(PutAssetBeginParams("image/png", size)).uploadId

    private fun refusedCode(block: () -> Unit): Int = assertFailsWith<LcpCallException> { block() }.code

    @Test
    fun aMultiChunkUploadRoundTripsThroughBothSessions() = runTest {
        val rig = LcpTestRig(backgroundScope).ready()
        val bytes = ByteArray(LcpPluginSession.CHUNK_BYTES * 2 + 17) { (it % 251).toByte() }
        val ref = rig.pluginSession.putAsset("image/png", bytes)
        val stored = rig.host.assets.single()
        assertEquals("sha256:${LcpAssetUploads.hex(Sha256.digest(bytes))}", ref)
        assertContentEquals(bytes, stored.bytes)
        assertEquals(3, LcpPluginSession.chunksOf(bytes).size)
        assertEquals(LcpWire.MAX_CHUNK_BASE64_CHARS, Base64.encode(LcpPluginSession.chunksOf(bytes).first()).length)
    }

    @Test
    fun aVerifiedUploadAnswersItsBytes() {
        val id = begin()
        uploads.chunk(PutAssetChunkParams(id, 0, Base64.encode(hello)))
        val asset = uploads.end(PutAssetEndParams(id, helloSha.uppercase()))
        assertEquals(helloSha, asset.sha256)
        assertContentEquals(hello, asset.bytes)
        assertEquals(0, uploads.openCount)
    }

    @Test
    fun aHashMismatchIsRefusedAndNothingStays() {
        val id = begin()
        uploads.chunk(PutAssetChunkParams(id, 0, Base64.encode(hello)))
        assertEquals(LcpErrorCode.UPLOAD_REFUSED, refusedCode { uploads.end(PutAssetEndParams(id, "00".repeat(32))) })
        assertEquals(0, uploads.openCount)
    }

    @Test
    fun aChunkForNoOpenUploadIsRefused() {
        assertEquals(LcpErrorCode.UPLOAD_REFUSED, refusedCode { uploads.chunk(PutAssetChunkParams("upload-404", 0, "aGVsbG8=")) })
    }

    @Test
    fun brokenChunksDropTheUpload() {
        val cases: List<(String) -> PutAssetChunkParams> = listOf(
            { PutAssetChunkParams(it, 1, "aGVsbG8=") },
            { PutAssetChunkParams(it, 0, "not base64!") },
            { PutAssetChunkParams(it, 0, Base64.encode("hello world".encodeToByteArray())) },
            { PutAssetChunkParams(it, 0, "A".repeat(LcpWire.MAX_CHUNK_BASE64_CHARS + 4)) },
        )
        cases.forEach { chunkOf ->
            val chunk = chunkOf(begin())
            assertEquals(LcpErrorCode.UPLOAD_REFUSED, refusedCode { uploads.chunk(chunk) }, chunk.base64.take(20))
            assertEquals(0, uploads.openCount, "the broken upload is dropped")
        }
    }

    @Test
    fun anIncompleteUploadIsRefusedAtTheEnd() {
        val id = begin(size = 10)
        uploads.chunk(PutAssetChunkParams(id, 0, Base64.encode(hello)))
        assertEquals(LcpErrorCode.UPLOAD_REFUSED, refusedCode { uploads.end(PutAssetEndParams(id, helloSha)) })
    }

    @Test
    fun sizesAndOpenUploadsAreCapped() {
        assertEquals(LcpErrorCode.UPLOAD_REFUSED, refusedCode { begin(size = LcpWire.MAX_ASSET_BYTES + 1) })
        assertEquals(LcpErrorCode.UPLOAD_REFUSED, refusedCode { uploads.begin(PutAssetBeginParams(" ", 1)) })
        repeat(LcpWire.MAX_OPEN_UPLOADS) { begin() }
        assertEquals(LcpErrorCode.UPLOAD_REFUSED, refusedCode { begin() })
    }
}
