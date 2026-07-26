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
            LivingComposerState(
                focused = false,
                text = "",
                hasStagedContent = false,
            ).isEngaged,
        )
    }

    @Test
    fun `focus engages composer without a draft`() {
        assertTrue(
            LivingComposerState(
                focused = true,
                text = "",
                hasStagedContent = false,
            ).isEngaged,
        )
    }

    @Test
    fun `draft engages composer without focus`() {
        assertTrue(
            LivingComposerState(
                focused = false,
                text = "Continue the analysis",
                hasStagedContent = false,
            ).isEngaged,
        )
    }

    @Test
    fun `staged content engages composer without text`() {
        assertTrue(
            LivingComposerState(
                focused = false,
                text = "",
                hasStagedContent = true,
            ).isEngaged,
        )
    }
}
