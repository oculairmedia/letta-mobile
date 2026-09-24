package com.letta.mobile.data.storage

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * An [AssetStore] on disk, for Android and desktop alike: each asset is `<directory>/<hash>` with
 * its media type beside it in `<hash>.type`. Names carry no extension, because an asset can be any
 * kind of file; the media type says what it is.
 *
 * Writes stage into the directory and move into place atomically, so a crash mid-write leaves no
 * half file under a content-addressed name (where dedupe would serve it forever). Reads re-hash
 * what they read and refuse anything that no longer matches its name.
 */
class FileAssetStore(
    private val directory: File,
    private val maxAssetBytes: Long = DEFAULT_MAX_ASSET_BYTES,
) : AssetStore {

    override fun put(mediaType: String, bytes: ByteArray): AssetRef {
        require(bytes.size <= maxAssetBytes) { "Asset of ${bytes.size} bytes exceeds the $maxAssetBytes byte limit" }
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create asset directory $directory" }
        val hash = sha256Hex(bytes)
        val asset = AssetRef(ref = AssetRefs.PREFIX + hash, mediaType = mediaType, byteSize = bytes.size.toLong())
        val file = fileFor(hash)
        // Written unless already here intact: a damaged copy under the name is replaced, not kept.
        if (get(asset.ref) == null) writeAtomically(file, bytes)
        val typeFile = typeFileFor(hash)
        if (!typeFile.isFile) writeAtomically(typeFile, mediaType.encodeToByteArray())
        return asset
    }

    override fun get(ref: String): ByteArray? {
        val hash = AssetRefs.hashOf(ref) ?: return null
        val file = fileFor(hash)
        if (!file.isFile || file.length() > maxAssetBytes) return null
        return runCatching {
            val bytes = file.inputStream().use { input ->
                val output = ByteArrayOutputStream(file.length().toInt())
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size().toLong() + count <= maxAssetBytes)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            bytes.takeIf { sha256Hex(it) == hash }
        }.getOrNull()
    }

    override fun has(ref: String): Boolean {
        val hash = AssetRefs.hashOf(ref) ?: return false
        return fileFor(hash).isFile
    }

    override fun mediaType(ref: String): String? {
        val hash = AssetRefs.hashOf(ref) ?: return null
        if (!fileFor(hash).isFile) return null
        return runCatching { typeFileFor(hash).readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun fileFor(hash: String): File = File(directory, hash).also { requireInside(it) }

    private fun typeFileFor(hash: String): File = File(directory, "$hash$TYPE_SUFFIX").also { requireInside(it) }

    /** A hash is 64 hex characters, so this never fails for one; it guards the rule, not the input. */
    private fun requireInside(file: File) {
        require(file.canonicalFile.parentFile == directory.canonicalFile) { "Asset path escapes $directory" }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        // A unique staging file per writer, so two writers of one asset cannot truncate each other.
        val staging = File.createTempFile("asset-", ".tmp", directory)
        try {
            staging.outputStream().use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            try {
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                // Staged in the same directory, so this is for exotic filesystems only; a real I/O
                // failure still propagates rather than publishing a partial copy.
                Files.move(staging.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            staging.delete()
        }
    }

    companion object {
        /** Generous for a pasted screenshot or a document; a video wants chunked storage later. */
        const val DEFAULT_MAX_ASSET_BYTES: Long = 50L * 1024 * 1024
        private const val TYPE_SUFFIX = ".type"
        private const val BUFFER_BYTES = 64 * 1024
    }
}

/** An [AssetStore] in memory: for tests, previews, and boards that are never saved. */
class InMemoryAssetStore : AssetStore {
    private val assets = ConcurrentHashMap<String, Pair<String, ByteArray>>()

    override fun put(mediaType: String, bytes: ByteArray): AssetRef {
        val ref = AssetRefs.PREFIX + sha256Hex(bytes)
        assets.putIfAbsent(ref, mediaType to bytes.copyOf())
        return AssetRef(ref = ref, mediaType = mediaType, byteSize = bytes.size.toLong())
    }

    override fun get(ref: String): ByteArray? = assets[ref]?.second?.copyOf()

    override fun has(ref: String): Boolean = assets.containsKey(ref)

    override fun mediaType(ref: String): String? = assets[ref]?.first
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
