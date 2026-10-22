package com.letta.mobile.ui.modelcontrol

import com.letta.mobile.data.repository.modelcontrol.ModelHandle
import com.letta.mobile.data.repository.modelcontrol.ModelPickerEntry
import com.letta.mobile.data.repository.modelcontrol.ReasoningTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-uljk9: a picker row's effort options, and the one thinking-to-effort mapping. */
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
}
