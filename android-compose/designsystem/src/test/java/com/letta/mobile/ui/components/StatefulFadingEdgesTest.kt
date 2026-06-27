package com.letta.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class StatefulFadingEdgesTest {

    @Test
    fun testCanScrollBackward() {
        assertEquals(false, calculateCanScrollBackward(0, 0))
        assertEquals(true, calculateCanScrollBackward(1, 0))
        assertEquals(true, calculateCanScrollBackward(0, 10))
        assertEquals(true, calculateCanScrollBackward(2, 50))
    }

    @Test
    fun testCanScrollForward_emptyList() {
        assertEquals(false, calculateCanScrollForward(null, null, 0, 100))
    }

    @Test
    fun testCanScrollForward_notAtEnd() {
        // Last visible index 1, end offset 100, total items 3 (so max index is 2), viewport 100
        assertEquals(true, calculateCanScrollForward(1, 100, 3, 100))
    }

    @Test
    fun testCanScrollForward_atEndButCutOff() {
        // Last visible index 1, total items 2 (at the end), but end offset 110 > viewport 100
        assertEquals(true, calculateCanScrollForward(1, 110, 2, 100))
    }

    @Test
    fun testCanScrollForward_atEndAndFullyVisible() {
        // Last visible index 1, total items 2 (at the end), end offset 100 == viewport 100
        assertEquals(false, calculateCanScrollForward(1, 100, 2, 100))
    }
}
