package io.ak1.drawbox.domain.model

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class SerializationTest {

    @Test
    fun everyChannelValueSurvivesAHexRoundTrip() {
        // Truncating k / 255f * 255 dropped some channels by one step on every save.
        for (k in 0..255) {
            val color = Color(red = k, green = k, blue = k, alpha = 255)
            assertEquals(color, color.toHexString().toColor(), "channel $k")
        }
    }
}
