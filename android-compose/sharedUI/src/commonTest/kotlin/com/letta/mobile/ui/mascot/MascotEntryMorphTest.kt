package com.letta.mobile.ui.mascot

import com.letta.mobile.avatar.core.HeadlessAvatarRuntime
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotPalette
import com.letta.mobile.avatar.core.MascotShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Any mascot becomes any other by morphing: the shape flips at once (the asset animates the
 * outline), the colour and the turn ease on the entry's own clock, and every host re-skins
 * through the same path.
 */
class MascotEntryMorphTest {
    private class RecordingEntry(identity: MascotIdentity) : MascotEntry(HeadlessAvatarRuntime(), identity, applyState = {}) {
        val written = mutableListOf<MascotIdentity>()

        override suspend fun load() = Unit

        override fun writeIdentity(identity: MascotIdentity) {
            written += identity
        }

        override fun dispose() = Unit
    }

    private val blueCircle = MascotIdentity(MascotShape.CIRCLE, MascotPalette.BLUE)
    private val redTriangle = MascotIdentity(MascotShape.TRIANGLE, MascotPalette.RED, rotationDegrees = 90)

    private fun RecordingEntry.tickFrames(count: Int, frameNanos: Long = 16_000_000L) {
        repeat(count) { i -> tickTo((i + 1) * frameNanos) }
    }

    @Test
    fun `the shape flips at the first write and the colour arrives with the last`() {
        val entry = RecordingEntry(blueCircle)
        entry.tickTo(1L) // first tick only sets the clock
        entry.retarget(redTriangle)

        val first = entry.written.first()
        assertEquals(MascotShape.TRIANGLE, first.shape)
        assertEquals(MascotPalette.BLUE, first.argb)
        assertEquals(redTriangle, entry.identity)

        entry.tickFrames(count = 30)
        assertEquals(redTriangle, entry.written.last())
        assertEquals(redTriangle, entry.shownIdentity())
    }

    @Test
    fun `colour and turn move monotonically while a morph is in flight`() {
        val entry = RecordingEntry(blueCircle)
        entry.tickTo(1L)
        entry.retarget(redTriangle)
        entry.tickFrames(count = 30)

        val reds = entry.written.map { (it.argb shr 16) and 0xff }
        assertEquals(reds.sorted(), reds, "red channel climbs from blue's to red's: $reds")
        val turns = entry.written.map { it.rotationDegrees }
        assertEquals(turns.sorted(), turns, "the body turns one way to 90: $turns")
        assertTrue(entry.written.size > 3, "a morph is several frames, not a cut")
    }

    @Test
    fun `no further writes once the morph has landed`() {
        val entry = RecordingEntry(blueCircle)
        entry.tickTo(1L)
        entry.retarget(redTriangle)
        entry.tickFrames(count = 30)
        val settled = entry.written.size
        entry.tickFrames(count = 10, frameNanos = 16_000_000L * 4)
        assertEquals(settled, entry.written.size)
    }

    @Test
    fun `zero seconds cuts straight to the target`() {
        val entry = RecordingEntry(blueCircle)
        entry.retarget(redTriangle, seconds = 0f)
        assertEquals(listOf(redTriangle), entry.written)
    }

    @Test
    fun `retargeting the same identity is a no-op`() {
        val entry = RecordingEntry(blueCircle)
        entry.retarget(blueCircle)
        assertTrue(entry.written.isEmpty())
    }

    @Test
    fun `a retarget mid-morph continues from what is shown rather than the old start`() {
        val entry = RecordingEntry(blueCircle)
        entry.tickTo(1L)
        entry.retarget(redTriangle)
        entry.tickFrames(count = 6) // ~96 ms of a 240 ms morph
        val shown = entry.shownIdentity()
        entry.retarget(blueCircle)
        assertEquals(shown.argb, entry.written.last().argb)
        assertEquals(MascotShape.CIRCLE, entry.written.last().shape)
    }
}
