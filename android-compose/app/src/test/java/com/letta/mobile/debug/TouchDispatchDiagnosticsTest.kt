package com.letta.mobile.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class TouchDispatchDiagnosticsTest {
    @Test
    fun logsStreamBoundariesButNotMoves() {
        listOf(0, 1, 3, 5, 6).forEach { assertTrue("action $it", isLoggedTouchAction(it)) }
        assertFalse(isLoggedTouchAction(2)) // MOVE
        assertFalse(isLoggedTouchAction(7)) // HOVER_MOVE
    }

    @Test
    fun namesActions() {
        assertEquals("DOWN", touchActionName(0))
        assertEquals("CANCEL", touchActionName(3))
        assertEquals("ACTION_2", touchActionName(2))
    }

    @Test
    fun namesStallRelevantFlags() {
        assertEquals("0x0[]", touchFlagNames(0))
        assertEquals("0x23[WINDOW_IS_OBSCURED,WINDOW_IS_PARTIALLY_OBSCURED,CANCELED]", touchFlagNames(0x23))
        assertEquals("0x800[]", touchFlagNames(0x800))
    }

    @Test
    fun summarizesPointerPhasesAndConsumption() {
        val summary = summarizePointerChanges(
            listOf(
                PointerChangeSnapshot(id = 4, pressed = true, previousPressed = false, consumed = true),
                PointerChangeSnapshot(id = 2, pressed = true, previousPressed = true, consumed = false),
                PointerChangeSnapshot(id = 3, pressed = false, previousPressed = true, consumed = false),
            ),
        )
        assertEquals("4:DOWN* 2:held 3:UP", summary)
    }
}
