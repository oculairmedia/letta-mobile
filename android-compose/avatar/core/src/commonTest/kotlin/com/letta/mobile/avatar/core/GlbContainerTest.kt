package com.letta.mobile.avatar.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlbContainerTest {
    @Test
    fun parsesJsonAndBinChunks() {
        val json = """{"asset":{"version":"2.0"}}"""
        val bin = byteArrayOf(1, 2, 3, 4)
        val glb = GlbContainer.parse(buildGlb(json, bin))

        assertEquals(2, glb.version)
        assertEquals(json, glb.json)
        assertContentEquals(bin, glb.binary)
    }

    @Test
    fun parsesJsonOnlyGlb() {
        val json = """{"asset":{"version":"2.0"}}"""
        val glb = GlbContainer.parse(buildGlb(json, bin = null))

        assertEquals(json, glb.json)
        assertNull(glb.binary)
    }

    @Test
    fun skipsUnknownChunkTypes() {
        val json = """{"a":1}"""
        val bytes = buildGlb(json, bin = null, leadingUnknownChunk = byteArrayOf(9, 9))
        assertEquals(json, GlbContainer.parse(bytes).json)
    }

    @Test
    fun looksLikeGlbSniffsMagic() {
        assertTrue(GlbContainer.looksLikeGlb(buildGlb("{}", null)))
        assertFalse(GlbContainer.looksLikeGlb("{\"json\":true}".encodeToByteArray()))
        assertFalse(GlbContainer.looksLikeGlb(ByteArray(0)))
    }

    @Test
    fun rejectsBadMagic() {
        val bytes = buildGlb("{}", null).also { it[0] = 'x'.code.toByte() }
        assertFailsWith<GlbFormatException> { GlbContainer.parse(bytes) }
        assertNull(GlbContainer.parseOrNull(bytes))
    }

    @Test
    fun rejectsWrongVersion() {
        val bytes = buildGlb("{}", null).also { it[4] = 1 }
        assertFailsWith<GlbFormatException> { GlbContainer.parse(bytes) }
    }

    @Test
    fun rejectsTruncatedBytes() {
        val bytes = buildGlb("""{"asset":{}}""", null)
        assertFailsWith<GlbFormatException> {
            GlbContainer.parse(bytes.copyOfRange(0, bytes.size - 4))
        }
    }

    @Test
    fun rejectsMissingJsonChunk() {
        // Container with only a BIN chunk.
        val bytes = buildRawGlb(listOf(GlbContainer.CHUNK_BIN to byteArrayOf(1)))
        assertFailsWith<GlbFormatException> { GlbContainer.parse(bytes) }
    }
}

/** Assemble a spec-shaped GLB from a JSON payload and optional BIN chunk. */
internal fun buildGlb(
    json: String,
    bin: ByteArray?,
    leadingUnknownChunk: ByteArray? = null,
): ByteArray {
    val chunks = buildList {
        leadingUnknownChunk?.let { add(0x12345678 to it) }
        add(GlbContainer.CHUNK_JSON to json.encodeToByteArray())
        bin?.let { add(GlbContainer.CHUNK_BIN to it) }
    }
    return buildRawGlb(chunks)
}

internal fun buildRawGlb(chunks: List<Pair<Int, ByteArray>>): ByteArray {
    val total = 12 + chunks.sumOf { 8 + it.second.size }
    val out = ByteArray(total)
    var offset = 0
    fun writeU32(value: Int) {
        out[offset] = (value and 0xFF).toByte()
        out[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        offset += 4
    }
    writeU32(GlbContainer.MAGIC)
    writeU32(2)
    writeU32(total)
    chunks.forEach { (type, data) ->
        writeU32(data.size)
        writeU32(type)
        data.copyInto(out, offset)
        offset += data.size
    }
    return out
}
