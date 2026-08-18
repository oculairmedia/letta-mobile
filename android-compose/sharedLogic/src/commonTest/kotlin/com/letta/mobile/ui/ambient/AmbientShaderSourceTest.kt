package com.letta.mobile.ui.ambient

import kotlin.test.Test
import kotlin.test.assertContains

class AmbientShaderSourceTest {
    @Test
    fun `stream energy speed lifts stay within ten percent`() {
        assertContains(AMBIENT_GLOW_SHADER_SOURCE, "0.09 + 0.009 * uStreamEnergy")
        assertContains(AMBIENT_GLOW_SHADER_SOURCE, "1.0 + 0.10 * uStreamEnergy")
    }
}
