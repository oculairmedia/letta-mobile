package com.letta.mobile.data.transport.iroh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IrohFramePublisherInitTest {

    @Test
    fun publisherRequiresPositiveBufferCapacity() {
        val zeroException = assertFailsWith<IllegalArgumentException> {
            IrohFramePublisher(bufferCapacity = 0)
        }
        assertEquals("bufferCapacity must be positive", zeroException.message)

        val negativeException = assertFailsWith<IllegalArgumentException> {
            IrohFramePublisher(bufferCapacity = -1)
        }
        assertEquals("bufferCapacity must be positive", negativeException.message)
    }

    @Test
    fun overflowBroadcastRequiresPositiveBufferCapacity() {
        val zeroException = assertFailsWith<IllegalArgumentException> {
            BoundedOverflowBroadcast(bufferCapacity = 0)
        }
        assertEquals("bufferCapacity must be positive", zeroException.message)

        val negativeException = assertFailsWith<IllegalArgumentException> {
            BoundedOverflowBroadcast(bufferCapacity = -1)
        }
        assertEquals("bufferCapacity must be positive", negativeException.message)
    }
}
