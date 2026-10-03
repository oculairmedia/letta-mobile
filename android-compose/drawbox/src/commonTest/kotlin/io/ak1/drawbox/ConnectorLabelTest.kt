package io.ak1.drawbox

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** A connector label sits on the shaft, and its chip is the board behind it. */
class ConnectorLabelTest {
    @Test
    fun theLabelSitsOnTheEndpointMidpoint() {
        assertEquals(Offset(50f, 20f), labelMidpoint(Offset(0f, 0f), Offset(100f, 40f)))
        assertEquals(Offset(10f, 10f), labelMidpoint(Offset(10f, 80f), Offset(10f, -60f)))
    }

    @Test
    fun theChipIsTheBoardBackground() {
        val dark = Color(0xFF101418)
        assertEquals(dark, connectorChip(dark))
        assertNotEquals(Color.White, connectorChip(dark))
        assertEquals(Color.White, connectorChip(Color.White))
    }
}
