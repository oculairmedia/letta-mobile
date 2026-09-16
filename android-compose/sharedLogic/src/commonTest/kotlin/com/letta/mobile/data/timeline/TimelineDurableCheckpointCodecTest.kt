package com.letta.mobile.data.timeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class TimelineDurableCheckpointCodecTest {
    @Test fun roundTripsEveryCursorAndExactIdentity() {
        val id = TimelineMessageId("id:/\\\"\n")
        listOf(null, TimelineContinuation.Initial, TimelineContinuation.Before(id), TimelineContinuation.After(id)).forEach {
            val checkpoint = TimelineDurableCheckpoint(Long.MAX_VALUE, it, true)
            assertEquals(checkpoint, TimelineDurableCheckpointCodec.decode(TimelineDurableCheckpointCodec.encode(checkpoint)))
        }
        val exhausted = TimelineDurableCheckpoint(0, null, false)
        assertEquals(exhausted, TimelineDurableCheckpointCodec.decode(TimelineDurableCheckpointCodec.encode(exhausted)))
    }

    @Test fun rejectsUnknownVersionAndMalformedUtf8() {
        assertFails { TimelineDurableCheckpointCodec.decode("{\"version\":2,\"revision\":0,\"kind\":\"none\",\"identity\":null,\"hasMore\":false}".encodeToByteArray()) }
        assertFails { TimelineDurableCheckpointCodec.decode(byteArrayOf(-1)) }
    }
}
