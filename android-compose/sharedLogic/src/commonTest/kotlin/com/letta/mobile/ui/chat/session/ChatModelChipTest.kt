package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.modelcontrol.CatalogModel
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChoices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-by4vm.85, ckmub.17.2: the composer model chip's mapping, shared by desktop and web. */
class ChatModelChipTest {
    private val chatModels = listOf(
        LlmModel(id = "sol", name = "Sol", handle = SOL),
        LlmModel(id = "minimax", name = "MiniMax", handle = MINIMAX),
    )

    @Test
    fun effortsAreKeyedBySelectionValueAndOffOnlyWhereUnsupported() {
        val catalog = listOf(CatalogModel(LlmModel(id = "sol", handle = SOL), exposed = true, reasoningEfforts = listOf("high")))
        val inputs = ChatModelEffortInputs(
            models = chatModels,
            catalog = catalog,
            choices = ConversationModelChoices(efforts = mapOf("conv-1" to "high")),
            selectedConversation = ConversationId("conv-1"),
            supported = true,
        )

        val efforts = chatModelEfforts(inputs)
        assertEquals(mapOf(SOL to listOf("high")), efforts.byValue)
        assertEquals("high", efforts.current)
        assertEquals(ChatModelEfforts(), chatModelEfforts(inputs.copy(supported = false)))

        val ui = chatModelUiState(SOL, listOf("Sol" to SOL, "MiniMax" to MINIMAX), efforts)
        assertEquals("high", ui.currentEffort)
        assertEquals(listOf("high"), ui.options.first().reasoningEfforts)
        assertTrue(ui.options.last().reasoningEfforts.isEmpty())
    }

    @Test
    fun modelChipMatchesOnLabelOrSelectionValue() {
        val options = listOf("Sonnet" to "anthropic/sonnet", "GPT" to "openai/gpt")

        val byValue = chatModelUiState("openai/gpt", options)
        assertEquals("openai/gpt", byValue.currentHandle)
        assertEquals("GPT", byValue.currentLabel)

        val byLabel = chatModelUiState("Sonnet", options)
        assertEquals("anthropic/sonnet", byLabel.currentHandle)

        val unknown = chatModelUiState("Auto", options)
        assertNull(unknown.currentHandle)
        assertEquals("Auto", unknown.currentLabel)
        assertEquals(listOf("anthropic/sonnet", "openai/gpt"), unknown.options.map { it.handle })
    }

    @Test
    fun optionsPreferTheDisplayOverrideAndKeepTheSelectionValue() {
        val models = listOf(LlmModel(id = "sol", name = "Sol", handle = SOL, displayNameOverride = "Sol (fast)"))

        assertEquals(listOf("Sol (fast)" to SOL), chatModelOptions(models))
    }

    private companion object {
        const val SOL = "openai/gpt-sol"
        const val MINIMAX = "lmstudio/minimax-m3"
    }
}
