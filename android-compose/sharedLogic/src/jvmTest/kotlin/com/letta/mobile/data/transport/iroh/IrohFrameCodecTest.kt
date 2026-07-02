package com.letta.mobile.data.transport.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IrohFrameCodecTest {
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
}
