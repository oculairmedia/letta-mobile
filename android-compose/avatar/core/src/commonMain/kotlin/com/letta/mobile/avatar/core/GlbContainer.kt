package com.letta.mobile.avatar.core

/** Thrown by [GlbContainer.parse] when bytes are not a valid GLB container. */
class GlbFormatException(message: String) : Exception(message)

/**
 * Minimal binary-glTF (GLB 2.0) container reader — pure Kotlin, no platform
 * IO — enough to pull the JSON chunk out of a `.glb`/`.vrm` for format
 * detection and manifest generation. Mesh/texture decoding stays in renderer
 * adapters; this is deliberately just the container framing.
 *
 * Layout per the glTF 2.0 spec: 12-byte header (magic `glTF`, version,
 * total length), then chunks of `[u32 length][u32 type][bytes]`, all u32s
 * little-endian. Chunk types: `JSON` (0x4E4F534A) and `BIN` (0x004E4942).
 */
object GlbContainer {
    const val MAGIC: Int = 0x46546C67 // "glTF" little-endian
    const val CHUNK_JSON: Int = 0x4E4F534A
    const val CHUNK_BIN: Int = 0x004E4942
    private const val HEADER_LENGTH = 12
    private const val CHUNK_HEADER_LENGTH = 8

    data class Glb(
        val version: Int,
        val json: String,
        val binary: ByteArray?,
    ) {
        override fun equals(other: Any?): Boolean =
            other is Glb && other.version == version && other.json == json &&
                (other.binary contentEquals binary)

        override fun hashCode(): Int =
            31 * (31 * version + json.hashCode()) + (binary?.contentHashCode() ?: 0)
    }

    /** True if [bytes] start with the GLB magic (cheap format sniff). */
    fun looksLikeGlb(bytes: ByteArray): Boolean =
        bytes.size >= 4 && readU32(bytes, 0) == MAGIC

    fun parseOrNull(bytes: ByteArray): Glb? =
        try {
            parse(bytes)
        } catch (_: GlbFormatException) {
            null
        }

    fun parse(bytes: ByteArray): Glb {
        if (bytes.size < HEADER_LENGTH) {
            throw GlbFormatException("Too short for a GLB header (${bytes.size} bytes)")
        }
        if (readU32(bytes, 0) != MAGIC) {
            throw GlbFormatException("Not a GLB container (bad magic)")
        }
        val version = readU32(bytes, 4)
        if (version != 2) {
            throw GlbFormatException("Unsupported GLB version $version (expected 2)")
        }
        val declaredLength = readU32(bytes, 8)
        if (declaredLength > bytes.size) {
            throw GlbFormatException(
                "Truncated GLB: header declares $declaredLength bytes, have ${bytes.size}",
            )
        }

        var offset = HEADER_LENGTH
        var json: String? = null
        var binary: ByteArray? = null
        while (offset + CHUNK_HEADER_LENGTH <= declaredLength) {
            val chunkLength = readU32(bytes, offset)
            val chunkType = readU32(bytes, offset + 4)
            val dataStart = offset + CHUNK_HEADER_LENGTH
            if (chunkLength < 0 || dataStart + chunkLength > declaredLength) {
                throw GlbFormatException("Truncated GLB chunk at offset $offset")
            }
            when (chunkType) {
                CHUNK_JSON -> if (json == null) {
                    json = bytes.decodeToString(dataStart, dataStart + chunkLength)
                }
                CHUNK_BIN -> if (binary == null) {
                    binary = bytes.copyOfRange(dataStart, dataStart + chunkLength)
                }
                // Unknown chunk types are skipped per spec.
            }
            offset = dataStart + chunkLength
        }

        return Glb(
            version = version,
            json = json ?: throw GlbFormatException("GLB has no JSON chunk"),
            binary = binary,
        )
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
