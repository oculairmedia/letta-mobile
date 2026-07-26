package com.letta.mobile.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class LettaInputBarLivingMotionTest {

    @Test
    fun `composer rests only when unfocused and empty`() {
        assertFalse(
            isLivingComposerEngaged(
                focused = false,
                text = "",
                hasStagedContent = false,
            ),
        )
    }

    @Test
    fun `focus engages composer without a draft`() {
        assertTrue(
            isLivingComposerEngaged(
                focused = true,
                text = "",
                hasStagedContent = false,
            ),
        )
    }

    @Test
    fun `draft engages composer without focus`() {
        assertTrue(
            isLivingComposerEngaged(
                focused = false,
                text = "Continue the analysis",
                hasStagedContent = false,
            ),
        )
    }

    @Test
    fun `staged content engages composer without text`() {
        assertTrue(
            isLivingComposerEngaged(
                focused = false,
                text = "",
                hasStagedContent = true,
            ),
        )
    }
}
