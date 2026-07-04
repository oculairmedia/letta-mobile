package com.letta.mobile.data.transport.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IrohFrameCodecTest {
    private val maxFrame = IrohFrameCodec.DEFAULT_MAX_FRAME_BYTES
    @Test
    fun multipleFramesCanArriveInOneChunk() {
        val decoder = IrohFrameCodec.Decoder()
        val chunk = IrohFrameCodec.encodeFrame("{\"type\":\"a\"}") +
            IrohFrameCodec.encodeFrame("{\"type\":\"b\"}")

        assertEquals(
            listOf("{\"type\":\"a\"}", "{\"type\":\"b\"}"),
            decoder.feed(chunk),
        )
        decoder.finish()
    }

    @Test
    fun oneFrameCanArriveAcrossManyChunks() {
        val decoder = IrohFrameCodec.Decoder()
        val encoded = IrohFrameCodec.encodeFrame("{\"type\":\"split\",\"value\":123}")
        val frames = mutableListOf<String>()

        encoded.forEach { byte ->
            frames += decoder.feed(byteArrayOf(byte))
        }

        assertEquals(listOf("{\"type\":\"split\",\"value\":123}"), frames)
        decoder.finish()
    }

    @Test
    fun oversizedFrameFailsExplicitlyWhenEncoding() {
        assertFailsWith<IllegalArgumentException> {
            IrohFrameCodec.encodeFrame(ByteArray(5), maxFrameBytes = 4)
        }
    }

    @Test
    fun oversizedFrameFailsExplicitlyWhenDecoding() {
        val encoded = IrohFrameCodec.encodeFrame(ByteArray(5), maxFrameBytes = 5)
        val decoder = IrohFrameCodec.Decoder(maxFrameBytes = 4)

        assertFailsWith<IrohFrameCodec.ProtocolException> {
            decoder.feed(encoded)
        }
    }

    @Test
    fun truncatedPrefixFailsExplicitlyOnFinish() {
        val decoder = IrohFrameCodec.Decoder()
        decoder.feed(byteArrayOf(0, 0))

        assertFailsWith<IrohFrameCodec.ProtocolException> {
            decoder.finish()
        }
    }

    @Test
    fun truncatedPayloadFailsExplicitlyOnFinish() {
        val decoder = IrohFrameCodec.Decoder()
        val encoded = IrohFrameCodec.encodeFrame("hello")
        decoder.feed(encoded.copyOfRange(0, encoded.size - 1))

        assertFailsWith<IrohFrameCodec.ProtocolException> {
            decoder.finish()
        }
    }

    // --- frame_part continuation encoding (letta-mobile-5purh) ---

    @Test
    fun framePartsRoundTripAtBoundarySizes() {
        // 1MiB-1 and 1MiB stay single plain frames; 1MiB+1 and 5MiB chunk.
        val cases = mapOf(
            maxFrame - 1 to 1,
            maxFrame to 1,
            maxFrame + 1 to 2,
            5 * maxFrame to 5,
        )
        cases.forEach { (size, expectedParts) ->
            val frame = asciiFrame(size)
            val buffers = IrohFrameCodec.encodeFrameParts(frame)
            assertEquals(expectedParts, buffers.size, "part count for size=$size")
            buffers.forEach { buffer ->
                assertTrue(buffer.size <= maxFrame + 13, "no wire buffer exceeds 1MiB + headers (size=$size)")
            }

            val decoder = IrohFrameCodec.Decoder()
            val frames = mutableListOf<String>()
            buffers.forEach { frames += decoder.feed(it) }
            decoder.finish()
            assertEquals(1, frames.size, "exactly one logical frame for size=$size")
            assertEquals(frame, frames.single(), "round-trip content for size=$size")
        }
    }

    @Test
    fun framePartsReassembleAcrossArbitraryChunkBoundaries() {
        val frame = asciiFrame(3 * maxFrame + 17)
        val wire = IrohFrameCodec.encodeFrameParts(frame).reduce(ByteArray::plus)
        val decoder = IrohFrameCodec.Decoder()
        val frames = mutableListOf<String>()

        var offset = 0
        while (offset < wire.size) {
            val end = minOf(offset + 8191, wire.size)
            frames += decoder.feed(wire.copyOfRange(offset, end))
            offset = end
        }
        decoder.finish()

        assertEquals(listOf(frame), frames)
    }

    @Test
    fun framePartsFollowedByPlainFrameDecodeInOrder() {
        val big = asciiFrame(maxFrame + 5)
        val decoder = IrohFrameCodec.Decoder()
        val frames = mutableListOf<String>()

        IrohFrameCodec.encodeFrameParts(big).forEach { frames += decoder.feed(it) }
        frames += decoder.feed(IrohFrameCodec.encodeFrame("{\"type\":\"after\"}"))
        decoder.finish()

        assertEquals(listOf(big, "{\"type\":\"after\"}"), frames)
    }

    @Test
    fun outOfSequencePartIsRejected() {
        val parts = IrohFrameCodec.encodeFrameParts(asciiFrame(3 * maxFrame))
        val decoder = IrohFrameCodec.Decoder()
        decoder.feed(parts[0])

        assertFailsWith<IrohFrameCodec.ProtocolException> {
            decoder.feed(parts[2]) // part 1 missing
        }
    }

    @Test
    fun duplicatePartIsRejected() {
        val parts = IrohFrameCodec.encodeFrameParts(asciiFrame(2 * maxFrame))
        val decoder = IrohFrameCodec.Decoder()
        decoder.feed(parts[0])

        assertFailsWith<IrohFrameCodec.ProtocolException> {
            decoder.feed(parts[0])
        }
    }

    @Test
    fun missingFinalPartFailsExplicitlyOnFinish() {
        val parts = IrohFrameCodec.encodeFrameParts(asciiFrame(2 * maxFrame))
        val decoder = IrohFrameCodec.Decoder()
        decoder.feed(parts[0])

        assertFailsWith<IrohFrameCodec.ProtocolException> {
            decoder.finish()
        }
    }

    @Test
    fun plainFrameInterleavedMidPartSequenceIsRejected() {
        val parts = IrohFrameCodec.encodeFrameParts(asciiFrame(2 * maxFrame))
        val decoder = IrohFrameCodec.Decoder()
        decoder.feed(parts[0])

        assertFailsWith<IrohFrameCodec.ProtocolException> {
            decoder.feed(IrohFrameCodec.encodeFrame("{\"type\":\"interleaved\"}"))
        }
    }

    @Test
    fun corruptPartFlagsAreRejected() {
        val parts = IrohFrameCodec.encodeFrameParts(asciiFrame(2 * maxFrame))
        val corrupted = parts[0].copyOf().also { it[4] = 0x7e } // unknown flag bits
        val decoder = IrohFrameCodec.Decoder()

        assertFailsWith<IrohFrameCodec.ProtocolException> {
            decoder.feed(corrupted)
        }
    }

    @Test
    fun reassemblyBeyondMemoryCapIsRejectedWithTypedError() {
        val parts = IrohFrameCodec.encodeFrameParts(
            asciiFrame(32).encodeToByteArray(),
            maxFrameBytes = 8,
            maxTotalBytes = 1_024,
        )
        assertEquals(4, parts.size)
        val decoder = IrohFrameCodec.Decoder(maxFrameBytes = 8, maxReassembledBytes = 20)
        decoder.feed(parts[0])
        decoder.feed(parts[1])

        assertFailsWith<IrohFrameCodec.FrameTooLargeException> {
            decoder.feed(parts[2]) // 24 accumulated bytes > 20 cap
        }
    }

    @Test
    fun capabilityOffSenderRejectsOversizedFrameWithTypedError() {
        // Without frame_part negotiation the writer must fail typed-and-clean
        // rather than emit continuation framing the peer cannot decode.
        assertFailsWith<IrohFrameCodec.FrameTooLargeException> {
            IrohFrameCodec.encodeFrame(asciiFrame(maxFrame + 1))
        }
    }

    @Test
    fun encoderRejectsLogicalFramesOverTotalCapWithTypedError() {
        assertFailsWith<IrohFrameCodec.FrameTooLargeException> {
            IrohFrameCodec.encodeFrameParts(ByteArray(64), maxFrameBytes = 8, maxTotalBytes = 32)
        }
    }

    private fun asciiFrame(sizeBytes: Int): String =
        String(CharArray(sizeBytes) { index -> 'a' + (index % 26) })
}
