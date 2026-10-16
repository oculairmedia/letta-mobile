package com.letta.mobile.avatar.core

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.nanoseconds

/** letta-mobile-te3n1.3: the body steps every frame, so a step must allocate nothing. */
class MascotBodyAllocationTest {
    @Test
    fun aStepAllocatesNothing() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val body = MascotBody()
        val hop = MascotVector(600f, -120f)
        val frame = FRAME_NANOS.nanoseconds
        val id = Thread.currentThread().id
        // Warm up so class loading and JIT are not counted.
        repeat(WARMUP) {
            body.hop(hop)
            repeat(FRAMES) { body.step(frame) }
        }
        body.hop(hop)
        var sink = 0f
        val before = threads.getThreadAllocatedBytes(id)
        repeat(FRAMES) {
            sink += body.step(frame) + body.scaleX + body.scaleY + body.rotationDegrees + body.motionX
        }
        val allocated = threads.getThreadAllocatedBytes(id) - before
        assertEquals(0L, allocated, "bytes allocated over $FRAMES steps (sink $sink)")
    }

    private companion object {
        const val WARMUP = 2_000
        const val FRAMES = 60
        const val FRAME_NANOS = 16_666_667L
    }
}
