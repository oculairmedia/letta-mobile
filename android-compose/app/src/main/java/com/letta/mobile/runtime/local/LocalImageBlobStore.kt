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
        get() = File(conversationDirectory, "blobs")

    override fun putBytes(mediaType: String, bytes: ByteArray): String {
        require(bytes.size <= MAX_BLOB_BYTES) { "Image exceeds blob budget" }
        check(blobsDirectory.mkdirs() || blobsDirectory.isDirectory)
        require(blobsDirectory.canonicalFile.parentFile == conversationDirectory.canonicalFile)
        val hash = sha256Hex(bytes)
        val ext = mediaTypeToExtension(mediaType)
        val ref = "sha256:$hash"
        val blobFile = File(blobsDirectory, "$hash.$ext")

        require(blobFile.canonicalFile.parentFile == blobsDirectory.canonicalFile)
        if (getBytes(ref)?.contentEquals(bytes) == true) return ref

        // Unique staging files prevent concurrent writers from truncating each other.
        val tmp = File.createTempFile("image-", ".tmp", blobsDirectory)
        try {
            tmp.outputStream().use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(tmp.renameTo(blobFile)) { "Atomic blob publication failed" }
        } finally {
            tmp.delete()
        }
        return ref
    }

    override fun getBytes(ref: String): ByteArray? {
        val hash = validatedHash(ref) ?: return null
        // Try all known extensions (we don't store mediaType in the ref).
        val candidates = listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        for (ext in candidates) {
            val blobFile = File(blobsDirectory, "$hash.$ext")
            if (blobFile.isFile) {
                return runCatching {
                    require(blobFile.canonicalFile.parentFile == blobsDirectory.canonicalFile)
                    require(blobFile.length() <= MAX_BLOB_BYTES)
                    val bytes = blobFile.inputStream().use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            require(output.size().toLong() + count <= MAX_BLOB_BYTES)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    require(bytes.size <= MAX_BLOB_BYTES && sha256Hex(bytes) == hash)
                    bytes
                }.getOrNull()
            }
        }
        return null
    }

    override fun has(ref: String): Boolean {
        val hash = validatedHash(ref) ?: return false
        val candidates = listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")
        return candidates.any { ext ->
            File(blobsDirectory, "$hash.$ext").isFile
        }
    }

    private fun validatedHash(ref: String): String? {
        if (!ref.matches(Regex("sha256:[a-f0-9]{64}"))) return null
        if (blobsDirectory.canonicalFile.parentFile != conversationDirectory.canonicalFile) return null
        return ref.removePrefix("sha256:")
    }

    companion object {
        const val MAX_BLOB_BYTES = 20 * 1024 * 1024
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
