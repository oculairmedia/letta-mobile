package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.ConversationModelSettings
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

    // letta-mobile-dxz5s: a fresh instance with no record() is the cold-start case; the host's
    // conversation record alone must name the model and its window.

    @Test
    fun aColdStartTakesTheHostsOverrideAndWindow() {
        val selections = ConversationModelSelections()
        selections.seedFromHost(listOf(hostConversation("conv-1", model = OPUS, window = 1_000_000, effort = "low")))

        assertEquals(OPUS, selections["conv-1"])
        assertEquals(OPUS, selections.effectiveModel("conv-1", agentModel = AGENT_MODEL))
        assertEquals(1_000_000, selections.choices.value.windows["conv-1"])
        assertEquals("low", selections.choices.value.efforts["conv-1"])
    }

    @Test
    fun aConversationWithoutAnOverrideRunsItsAgentsModel() {
        val selections = ConversationModelSelections()
        selections.seedFromHost(listOf(hostConversation("conv-1", model = null)))

        assertNull(selections["conv-1"])
        assertEquals(AGENT_MODEL, selections.effectiveModel("conv-1", agentModel = AGENT_MODEL))
        assertNull(selections.choices.value.windows["conv-1"])
    }

    @Test
    fun aPickMadeHereWinsUntilTheHostConfirmsIt() {
        val selections = ConversationModelSelections()
        selections.seedFromHost(listOf(hostConversation("conv-1", model = OPUS, window = 1_000_000)))
        selections.record("conv-1", GPT)

        // A list fetched before the switch landed still says opus: the pick stands, and opus's
        // window no longer describes the conversation's model.
        selections.seedFromHost(listOf(hostConversation("conv-1", model = OPUS, window = 1_000_000)))
        assertEquals(GPT, selections["conv-1"])
        assertNull(selections.choices.value.windows["conv-1"])

        // The host confirms the pick: its record (and window) is the truth again.
        selections.seedFromHost(listOf(hostConversation("conv-1", model = GPT, window = 400_000)))
        assertEquals(GPT, selections["conv-1"])
        assertEquals(400_000, selections.choices.value.windows["conv-1"])

        // A later change elsewhere now shows, since no local pick is pending.
        selections.seedFromHost(listOf(hostConversation("conv-1", model = OPUS, window = 1_000_000)))
        assertEquals(OPUS, selections["conv-1"])
    }

    @Test
    fun aConversationLimitWithoutAModelOverrideSizesTheAgentsModel() {
        val selections = ConversationModelSelections()
        selections.seedFromHost(listOf(hostConversation("conv-1", model = null, window = 64_000)))

        assertNull(selections["conv-1"])
        assertEquals(64_000, selections.choices.value.windows["conv-1"])
    }

    private fun hostConversation(id: String, model: String?, window: Int? = null, effort: String? = null) = Conversation(
        id = ConversationId(id),
        agentId = AgentId("agent-1"),
        model = model,
        modelSettings = if (window != null || effort != null) ConversationModelSettings(contextWindowLimit = window, effort = effort) else null,
    )

    private companion object {
        const val OPUS = "anthropic/claude-opus-5-5"
        const val GPT = "openai/gpt-5.6-sol"
        const val AGENT_MODEL = "lmstudio/gpt-6-sol"
    }
}
