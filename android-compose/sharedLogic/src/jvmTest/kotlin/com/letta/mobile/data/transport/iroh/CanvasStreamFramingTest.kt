package com.letta.mobile.data.transport.iroh

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class CanvasStreamFramingTest {
    @Test
    fun frameUsesBigEndianLengthAndPreservesPayload() {
        val payload = ByteArray(256) { it.toByte() }
        val frame = encodeCanvasFrame(payload, 256, "Canvas")
        assertContentEquals(byteArrayOf(0, 0, 1, 0) + payload, frame)
    }

    @Test
    fun rejectsPayloadAboveTransportLimit() {
        assertFailsWith<IllegalArgumentException> { encodeCanvasFrame(ByteArray(2), 1, "Canvas") }
    }
}
