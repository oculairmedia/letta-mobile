package com.letta.mobile.runtime.local

import com.letta.mobile.data.storage.ImageBlobStore
import java.io.File
import java.security.MessageDigest

/**
 * Filesystem-backed implementation of [ImageBlobStore] for the embedded
 * runtime (letta-mobile-xybm2).
 *
 * Blobs are stored at `conversations/<convKey>/blobs/<sha256>.<ext>` —
 * sidecar to messages.jsonl. The sha256 hash ensures content addressing
 * (identical images dedupe) and stable refs across app restarts.
 *
 * Thread-safe: all write operations use atomic file renames.
 */
class LocalImageBlobStore(
    private val conversationDirectory: File,
) : ImageBlobStore {
    private val blobsDirectory: File
        get() = File(conversationDirectory, "blobs").apply { mkdirs() }

    override fun putBytes(mediaType: String, bytes: ByteArray): String {
        val hash = sha256Hex(bytes)
        val ext = mediaTypeToExtension(mediaType)
        val ref = "sha256:$hash"
        val blobFile = File(blobsDirectory, "$hash.$ext")

        // Idempotent: if the blob already exists, skip the write.
        if (blobFile.isFile) return ref

        // Atomic write: tmp file → rename.
        val tmp = File(blobsDirectory, "$hash.$ext.tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(blobFile)) {
            // Fallback: direct write if rename fails (shouldn't happen).
            blobFile.writeBytes(bytes)
            tmp.delete()
        }
        return ref
    }

    override fun getBytes(ref: String): ByteArray? {
        val hash = ref.removePrefix("sha256:").takeIf { it.isNotBlank() } ?: return null
        // Try all known extensions (we don't store mediaType in the ref).
        val candidates = listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        for (ext in candidates) {
            val blobFile = File(blobsDirectory, "$hash.$ext")
            if (blobFile.isFile) {
                return runCatching { blobFile.readBytes() }.getOrNull()
            }
        }
        return null
    }

    override fun has(ref: String): Boolean {
        val hash = ref.removePrefix("sha256:").takeIf { it.isNotBlank() } ?: return false
        val candidates = listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        return candidates.any { ext ->
            File(blobsDirectory, "$hash.$ext").isFile
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun mediaTypeToExtension(mediaType: String): String =
        when {
            mediaType.contains("jpeg") -> "jpg"
            mediaType.contains("jpg") -> "jpg"
            mediaType.contains("png") -> "png"
            mediaType.contains("gif") -> "gif"
            mediaType.contains("webp") -> "webp"
            mediaType.contains("bmp") -> "bmp"
            else -> "jpg" // fallback
        }
}
