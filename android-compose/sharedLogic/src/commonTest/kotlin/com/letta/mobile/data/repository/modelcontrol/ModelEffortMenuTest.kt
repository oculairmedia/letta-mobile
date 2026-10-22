package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.model.LlmModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flowOf

/** letta-mobile-uljk9: a picker row's effort options and the effort the picker remembers per model. */
class ModelEffortMenuTest {
    private fun entry(handle: String, efforts: List<String>, tier: ReasoningTier? = null) = ModelPickerEntry(
        value = handle,
        handle = ModelHandle(handle),
        displayName = handle,
        tier = tier,
        efforts = efforts,
        selected = false,
    )

    @Test
    fun thinkingIsOffWhileTheEffortIsNoneAndAFlipPicksTheOtherSide() {
        val levels = listOf("none", "low", "high")

        assertEquals(ThinkingToggle(on = true, toggledEffort = "none"), thinkingToggleOf(levels, current = "high", defaultEffort = "low"))
        assertEquals(ThinkingToggle(on = false, toggledEffort = "low"), thinkingToggleOf(levels, current = "none", defaultEffort = "low"))
        // A default that is itself "off" turns thinking back on at the provider default.
        assertEquals(ThinkingToggle(on = false, toggledEffort = null), thinkingToggleOf(levels, current = "none", defaultEffort = "none"))
    }

    @Test
    fun aModelWithoutAnOffLevelOrWithOnlyOneHasNoThinkingSwitch() {
        assertNull(thinkingToggleOf(listOf("low", "high"), current = "low", defaultEffort = null))
        assertNull(thinkingToggleOf(listOf("none"), current = null, defaultEffort = null))
    }

    @Test
    fun theMenuListsLevelsWithoutTheOffOneAndTheRememberedEffortWinsOverTheDefault() {
        val gpt = entry("openai/gpt", listOf("none", "low", "medium", "high"), tier = ReasoningTier.MEDIUM)

        val fresh = gpt.effortMenu(remembered = null)
        assertEquals(listOf("low", "medium", "high"), fresh.levels)
        assertEquals("medium", fresh.current)
        assertEquals(true, fresh.thinking?.on)

        assertEquals("high", gpt.effortMenu(remembered = "high").current)
        assertTrue(entry("plain/model", emptyList()).effortMenu(remembered = null).isEmpty)
    }

    private fun binding(
        models: MutableList<String>,
        efforts: MutableList<Pair<String, String?>>,
        withEfforts: Boolean = true,
    ) = ModelPickerBinding(
        source = ModelPickerSource.of(flowOf(emptyList<LlmModel>())) {},
        onModelSelected = { models += it.value },
        onEffortSelected = if (withEfforts) ({ entry, effort -> efforts += entry.value to effort }) else null,
        editor = null,
        onAddProvider = null,
        onModelsEdited = {},
    )

    @Test
    fun aRowPickUsesTheHostDefaultUntilAnEffortWasPickedForThatModel() {
        val models = mutableListOf<String>()
        val efforts = mutableListOf<Pair<String, String?>>()
        val picker = binding(models, efforts)
        val gpt = entry("openai/gpt", listOf("low", "high"))
        val other = entry("anthropic/claude", listOf("low", "high"))

        picker.selectModel(gpt)
        picker.selectEffort(gpt, "high")
        picker.selectModel(other)
        picker.selectModel(gpt)

        assertEquals(listOf("openai/gpt", "anthropic/claude"), models)
        assertEquals(listOf<Pair<String, String?>>("openai/gpt" to "high", "openai/gpt" to "high"), efforts)
        assertEquals<String?>("high", picker.rememberedEffort(gpt))
        assertNull(picker.rememberedEffort(other))
    }

    @Test
    fun aProviderDefaultPickForgetsTheModelsEffortAndAHostWithoutEffortsOnlySwitchesModels() {
        val models = mutableListOf<String>()
        val efforts = mutableListOf<Pair<String, String?>>()
        val picker = binding(models, efforts)
        val gpt = entry("openai/gpt", listOf("low", "high"))

        picker.selectEffort(gpt, "low")
        picker.selectEffort(gpt, null)
        assertNull(picker.rememberedEffort(gpt))

        val plain = binding(models, efforts, withEfforts = false)
        plain.selectEffort(gpt, "high")
        plain.selectModel(gpt)
        assertEquals(listOf("openai/gpt"), models)
        assertNull(plain.rememberedEffort(gpt))
    }
}
