package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.model.LlmModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** letta-mobile-by4vm.85: a chat model list row finds its catalog efforts by handle, id or alias. */
class ReasoningEffortsOfTest {
    private val rows = listOf(
        CatalogModel(LlmModel(id = "sol", handle = "openai/gpt-sol"), exposed = true, reasoningEfforts = listOf("low", "high")),
        CatalogModel(LlmModel(id = "mm", handle = "lmstudio/minimax-m3"), exposed = true, reasoningEfforts = emptyList()),
    )

    @Test
    fun matchesOnTheHandle() {
        assertEquals(listOf("low", "high"), reasoningEffortsOf(rows, LlmModel(id = "x", handle = "openai/gpt-sol")))
    }

    @Test
    fun matchesOnTheIdOrASelectionAlias() {
        assertEquals(listOf("low", "high"), reasoningEffortsOf(rows, LlmModel(id = "openai/gpt-sol")))
        val aliased = LlmModel(id = "y", handle = "openai/gpt-sol-alias", selectionAliases = setOf("openai/gpt-sol"))
        assertEquals(listOf("low", "high"), reasoningEffortsOf(rows, aliased))
    }

    @Test
    fun aModelWithoutEffortsOrWithoutARowHasNone() {
        assertTrue(reasoningEffortsOf(rows, LlmModel(id = "mm", handle = "lmstudio/minimax-m3")).isEmpty())
        assertTrue(reasoningEffortsOf(rows, LlmModel(id = "z", handle = "other/model")).isEmpty())
        assertTrue(reasoningEffortsOf(emptyList(), LlmModel(id = "sol", handle = "openai/gpt-sol")).isEmpty())
    }
}
