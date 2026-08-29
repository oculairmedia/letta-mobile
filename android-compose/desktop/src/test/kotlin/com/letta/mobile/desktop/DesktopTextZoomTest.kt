package com.letta.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopTextZoomTest {
    @Test
    fun `scroll up zooms in and scroll down zooms out`() {
        assertTrue(nextTextZoom(DEFAULT_TEXT_ZOOM, -1f) > DEFAULT_TEXT_ZOOM)
        assertTrue(nextTextZoom(DEFAULT_TEXT_ZOOM, 1f) < DEFAULT_TEXT_ZOOM)
    }

    @Test
    fun `zero delta is a no-op`() {
        assertEquals(1.23f, nextTextZoom(1.23f, 0f))
    }

    @Test
    fun `zoom is clamped at both ends`() {
        var zoomedOut = DEFAULT_TEXT_ZOOM
        repeat(50) { zoomedOut = nextTextZoom(zoomedOut, 1f) }
        assertEquals(MIN_TEXT_ZOOM, zoomedOut)

        var zoomedIn = DEFAULT_TEXT_ZOOM
        repeat(50) { zoomedIn = nextTextZoom(zoomedIn, -1f) }
        assertEquals(MAX_TEXT_ZOOM, zoomedIn)
    }

    /** In and back out must land exactly on 1.0, not 0.9999998. */
    @Test
    fun `round trip returns to the neutral scale`() {
        val inOnce = nextTextZoom(DEFAULT_TEXT_ZOOM, -1f)
        assertEquals(DEFAULT_TEXT_ZOOM, nextTextZoom(inOnce, 1f))
    }

    @Test
    fun `every reachable step stays within bounds`() {
        var zoom = DEFAULT_TEXT_ZOOM
        repeat(100) {
            zoom = nextTextZoom(zoom, if (it % 3 == 0) 1f else -1f)
            assertTrue(zoom in MIN_TEXT_ZOOM..MAX_TEXT_ZOOM, "out of bounds: $zoom")
        }
    }
}
