package com.letta.mobile.data.plugin.wire

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** NDJSON framing for the `process` runtime (letta-mobile-s416w.25): lines across chunks, CRLF, blank lines, oversized lines. */
class NdjsonFramingTest {
    private fun text(value: String): LcpInboundFrame = LcpInboundFrame.Text(value)

    @Test
    fun linesSplitAcrossChunksComeOutWhole() {
        val framer = NdjsonFramer()
        assertEquals(emptyList(), framer.feed("{\"a\":".encodeToByteArray()))
        assertEquals(listOf(text("{\"a\":1}"), text("{\"b\":2}")), framer.feed("1}\n{\"b\":2}\n{\"c\"".encodeToByteArray()))
        assertEquals(listOf(text("{\"c\":3}")), framer.feed(":3}\n".encodeToByteArray()))
    }

    @Test
    fun aCarriageReturnBeforeTheNewlineAndBlankLinesAreTolerated() {
        val framer = NdjsonFramer()
        assertEquals(listOf(text("{}"), text("[]")), framer.feed("{}\r\n\n  \n[]\n".encodeToByteArray()))
    }

    @Test
    fun multiByteCharactersSplitAcrossChunksDecode() {
        val bytes = "\"é😀\"\n".encodeToByteArray()
        val framer = NdjsonFramer()
        val frames = bytes.indices.flatMap { framer.feed(byteArrayOf(bytes[it])) }
        assertEquals(listOf(text("\"é😀\"")), frames)
    }

    @Test
    fun anOversizedLineIsDroppedAndTheNextLineStillReads() {
        val framer = NdjsonFramer(maxLineBytes = 8)
        val frames = framer.feed("0123456".encodeToByteArray()) + framer.feed("789abc\n{}\n".encodeToByteArray())
        assertEquals(listOf(LcpInboundFrame.Oversized(13), text("{}")), frames)
    }

    @Test
    fun theLastLineWithoutANewlineIsReadAtTheEnd() {
        val framer = NdjsonFramer()
        assertEquals(emptyList(), framer.feed("{}".encodeToByteArray()))
        assertEquals(text("{}"), framer.finish())
        assertNull(framer.finish())
    }

    @Test
    fun theTransportWritesOneLinePerMessageAndReadsUntilTheStreamEnds() = runTest {
        val chunks = ArrayDeque(listOf("{\"x\":1}\n{\"y\"".encodeToByteArray(), ":2}".encodeToByteArray()))
        val sink = RecordingSink()
        val transport = NdjsonLcpTransport({ chunks.removeFirstOrNull() }, sink)
        transport.send("{\"a\":1}")
        transport.send("{\"b\":2}")
        assertEquals("{\"a\":1}\n{\"b\":2}\n", sink.written.decodeToString())
        assertEquals(listOf(text("{\"x\":1}"), text("{\"y\":2}"), null), List(3) { transport.receive() })
        assertFailsWith<IllegalArgumentException> { transport.send("{\n}") }
    }

    @Test
    fun aWebSocketFrameOverTheCapIsOversized() = runTest {
        val frames = ArrayDeque(listOf("{}", "x".repeat(LcpWire.MAX_MESSAGE_BYTES + 1)))
        val transport = TextFrameLcpTransport(QueueFrames(frames))
        assertEquals(text("{}"), transport.receive())
        assertEquals(LcpInboundFrame.Oversized(LcpWire.MAX_MESSAGE_BYTES + 1L), transport.receive())
        assertNull(transport.receive())
    }

    private class RecordingSink : LcpByteSink {
        var written = ByteArray(0)

        override suspend fun write(bytes: ByteArray) {
            written += bytes
        }

        override fun close() {}
    }

    private class QueueFrames(private val frames: ArrayDeque<String>) : LcpTextFrames {
        override suspend fun send(text: String) {}

        override suspend fun receive(): String? = frames.removeFirstOrNull()

        override fun close() {}
    }
}
