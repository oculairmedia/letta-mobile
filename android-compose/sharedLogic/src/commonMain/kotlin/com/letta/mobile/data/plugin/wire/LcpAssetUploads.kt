package com.letta.mobile.data.plugin.wire

import com.letta.mobile.data.canvas.compose.Sha256
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** One finished upload: every byte, verified against the plugin's SHA-256. */
class LcpUploadedAsset(val mediaType: String, val bytes: ByteArray, val sha256: String)

/**
 * The host's half of `host.putAsset.begin/chunk/end` (plan section 5): at most
 * [LcpWire.MAX_OPEN_UPLOADS] open uploads, each at most [LcpWire.MAX_ASSET_BYTES], chunks in order
 * from 0 of at most [LcpWire.MAX_CHUNK_BYTES] bytes each, the total exactly the declared
 * size, and the lowercase hex SHA-256 matching. Any broken rule refuses the call with
 * [LcpErrorCode.UPLOAD_REFUSED] and drops the upload, so nothing half-sent is ever stored.
 */
class LcpAssetUploads {
    private class Upload(val mediaType: String, val byteSize: Int) {
        val bytes = ByteArray(byteSize)
        var received = 0
        var nextIndex = 0
    }

    private val lock = SynchronizedObject()
    private val open = mutableMapOf<String, Upload>()
    private var nextId = 0

    fun begin(params: PutAssetBeginParams): PutAssetBeginResult = synchronized(lock) {
        if (params.mediaType.isBlank()) refuse("mediaType is required")
        if (params.byteSize !in 0..LcpWire.MAX_ASSET_BYTES) refuse("an asset is at most ${LcpWire.MAX_ASSET_BYTES} bytes")
        if (open.size >= LcpWire.MAX_OPEN_UPLOADS) refuse("at most ${LcpWire.MAX_OPEN_UPLOADS} uploads may be open")
        val uploadId = "upload-${++nextId}"
        open[uploadId] = Upload(params.mediaType, params.byteSize.toInt())
        PutAssetBeginResult(uploadId)
    }

    fun chunk(params: PutAssetChunkParams) {
        if (params.base64.length > LcpWire.MAX_CHUNK_BASE64_CHARS) drop(params.uploadId, "a chunk is at most ${LcpWire.MAX_CHUNK_BYTES} bytes")
        val bytes = decode(params)
        val problem = synchronized(lock) { append(openUpload(params.uploadId), params.index, bytes) }
        problem?.let { drop(params.uploadId, it) }
    }

    /** Appends chunk [index]; the reason it cannot be, or null once it is. */
    private fun append(upload: Upload, index: Int, bytes: ByteArray): String? = when {
        index != upload.nextIndex -> "chunk $index out of order; expected ${upload.nextIndex}"
        upload.received + bytes.size > upload.byteSize -> "more bytes than the declared ${upload.byteSize}"
        else -> {
            bytes.copyInto(upload.bytes, upload.received)
            upload.received += bytes.size
            upload.nextIndex++
            null
        }
    }

    fun end(params: PutAssetEndParams): LcpUploadedAsset {
        val upload = synchronized(lock) { openUpload(params.uploadId).also { open.remove(params.uploadId) } }
        if (upload.received != upload.byteSize) refuse("received ${upload.received} of ${upload.byteSize} bytes")
        val actual = hex(Sha256.digest(upload.bytes))
        if (actual != params.sha256.lowercase()) refuse("sha256 mismatch: the bytes hash to $actual")
        return LcpUploadedAsset(upload.mediaType, upload.bytes, actual)
    }

    val openCount: Int get() = synchronized(lock) { open.size }

    private fun openUpload(uploadId: String): Upload = open[uploadId] ?: refuse("no open upload $uploadId")

    @OptIn(ExperimentalEncodingApi::class)
    private fun decode(params: PutAssetChunkParams): ByteArray = try {
        Base64.decode(params.base64)
    } catch (_: IllegalArgumentException) {
        drop(params.uploadId, "chunk ${params.index} is not base64")
    }

    private fun drop(uploadId: String, reason: String): Nothing {
        synchronized(lock) { open.remove(uploadId) }
        refuse(reason)
    }

    private fun refuse(reason: String): Nothing = throw LcpCallException(LcpErrorCode.UPLOAD_REFUSED, reason)

    companion object {
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
