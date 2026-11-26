package com.letta.mobile.data.transport.iroh

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The language-neutral codec vectors of node protocol v1 (docs/reference/contracts/node-protocol/codec-vectors-v1.json),
 * run through this node's [IrohFrameCodec]. The Rust core runs the same file, so both codecs are held to one wire.
 */
class IrohFrameCodecVectorsTest {
    private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .first { File(it, "scripts/ci/check-wire-contracts.py").isFile }
    private val fixture = Json.parseToJsonElement(
        File(root, "docs/reference/contracts/node-protocol/codec-vectors-v1.json").readText(),
    ).jsonObject
    private val defaults = fixture.getValue("default_limits").jsonObject
    private val vectors = fixture.getValue("vectors").jsonArray.map { it.jsonObject }

    @Test
    fun theDefaultLimitsAreTheCodecs() {
        assertEquals(IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES, defaults.int("max_frame_bytes"))
        assertEquals(IrohFrameCodec.DEFAULT_MAX_REASSEMBLED_BYTES, defaults.int("max_reassembled_bytes"))
    }

    @Test
    fun encodersProduceEveryVectorExactly() {
        val encoded = vectors.filter { "encode" in it && "payload" in it }
        assertTrue(encoded.size >= 10)
        encoded.forEach { vector ->
            val name = vector.string("name")
            val (maxFrame, maxTotal) = limits(vector)
            val payload = bytes(vector.getValue("payload").jsonArray)
            val encode = {
                when (vector.string("encode")) {
                    "plain" -> listOf(IrohFrameCodec.encodeFrame(payload, maxFrame))
                    "parts" -> IrohFrameCodec.encodeFrameParts(payload, maxFrame, maxTotal)
                    else -> fail("$name: unknown encoder")
                }
            }
            if ("encode_error" in vector) {
                assertFailsWith<IrohFrameCodec.FrameTooLargeException>(name) { encode() }
            } else {
                val expected = vector.getValue("wire_parts").jsonArray.map { bytes(it.jsonArray) }
                val produced = encode()
                assertEquals(expected.size, produced.size, "$name: buffer count")
                expected.zip(produced).forEach { (want, got) -> assertContentEquals(want, got, name) }
            }
        }
    }

    @Test
    fun decodersProduceEveryVectorWholeAndByteByByte() {
        val decoded = vectors.filter { "wire" in it || "wire_parts" in it }
        assertTrue(decoded.size >= 30)
        decoded.forEach { vector ->
            val wire = vector["wire"]?.let { bytes(it.jsonArray) }
                ?: vector.getValue("wire_parts").jsonArray.map { bytes(it.jsonArray) }.reduce(ByteArray::plus)
            val chunks = listOf(wire.size, if (wire.size > 65_536) 4_093 else 1)
            chunks.forEach { chunk -> checkDecode(vector, wire, chunk) }
        }
    }

    private fun checkDecode(vector: JsonObject, wire: ByteArray, chunk: Int) {
        val name = "${vector.string("name")} (chunk $chunk)"
        val (maxFrame, maxTotal) = limits(vector)
        val decoder = IrohFrameCodec.Decoder(maxFrame, maxTotal)
        val frames = mutableListOf<String>()
        val error = vector["error"]?.jsonObject
        if (error == null) {
            wire.chunked(chunk).forEach { frames += decoder.feed(it) }
            decoder.finish()
            assertEquals(vector.getValue("frames").jsonArray.map { text(it.jsonArray) }, frames, name)
            return
        }
        if (error.string("kind") == "invalid_utf8") {
            // Known divergence, recorded in the vector: this codec replaces malformed UTF-8 instead of refusing it.
            wire.chunked(chunk).forEach { frames += decoder.feed(it) }
            assertTrue(frames.single().contains('�'), name)
            return
        }
        when (error.string("at")) {
            "push" -> assertFailsWith<IrohFrameCodec.ProtocolException>(name) {
                wire.chunked(chunk).forEach { frames += decoder.feed(it) }
            }
            "finish" -> {
                wire.chunked(chunk).forEach { frames += decoder.feed(it) }
                assertFailsWith<IrohFrameCodec.ProtocolException>(name) { decoder.finish() }
            }
            else -> fail("$name: unknown error position")
        }
        if (chunk == 1) {
            val before = error["frames_before"]?.jsonArray?.map { text(it.jsonArray) }.orEmpty()
            assertEquals(before, frames, "$name: frames before the error")
        }
    }

    private fun limits(vector: JsonObject): Pair<Int, Int> {
        val limits = vector["limits"]?.jsonObject ?: defaults
        return limits.int("max_frame_bytes") to limits.int("max_reassembled_bytes")
    }

    private fun text(segments: JsonArray): String = bytes(segments).decodeToString()

    /** Expands the fixture's byte-vector notation: hex, utf8 and repeat_hex segments, concatenated. */
    private fun bytes(segments: JsonArray): ByteArray = segments.map { it.jsonObject }.fold(ByteArray(0)) { acc, segment ->
        acc + when {
            "hex" in segment -> hex(segment.string("hex"))
            "utf8" in segment -> segment.string("utf8").encodeToByteArray()
            else -> {
                val unit = hex(segment.string("repeat_hex"))
                ByteArray(unit.size * segment.int("count")) { unit[it % unit.size] }
            }
        }
    }

    private fun hex(digits: String): ByteArray = digits.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

    private fun ByteArray.chunked(size: Int): List<ByteArray> =
        (indices step size).map { copyOfRange(it, minOf(it + size, this.size)) }
}
