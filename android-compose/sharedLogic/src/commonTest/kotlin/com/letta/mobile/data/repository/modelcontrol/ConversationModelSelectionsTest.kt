package com.letta.mobile.data.repository.modelcontrol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationModelSelectionsTest {
    @Test
    fun aFailedSwitchRestoresThePreviousPick() {
        val selections = ConversationModelSelections()
        selections.record("conv-1", "openai/gpt-5.6-sol")
        selections.record("conv-1", "lmstudio/minimax-m3")

        assertTrue(selections.rollback("conv-1", "lmstudio/minimax-m3", previous = "openai/gpt-5.6-sol"))
        assertEquals("openai/gpt-5.6-sol", selections["conv-1"])
    }

    @Test
    fun anEarlierFailureDoesNotUndoANewerPick() {
        val selections = ConversationModelSelections()
        selections.record("conv-1", "lmstudio/minimax-m3")
        selections.record("conv-1", "anthropic/claude-fable-5-1")

        assertFalse(selections.rollback("conv-1", "lmstudio/minimax-m3", previous = null))
        assertEquals("anthropic/claude-fable-5-1", selections["conv-1"])
    }

    @Test
    fun rollingBackToNoPickClearsTheOverride() {
        val selections = ConversationModelSelections()
        selections.record("conv-1", "lmstudio/minimax-m3")

        assertTrue(selections.rollback("conv-1", "lmstudio/minimax-m3", previous = null))
        assertNull(selections["conv-1"])
    }
}
