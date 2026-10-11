@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.context

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.context.AgentContextCardInputs
import com.letta.mobile.data.context.AgentContextCardModel
import com.letta.mobile.data.context.ContextMeter
import com.letta.mobile.data.context.limit.ContextLimitScope
import com.letta.mobile.data.context.limit.ContextLimitStops
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-joigh: the sheet's context-limit slider. */
class ContextLimitSliderUiTest {
    private val stops = ContextLimitStops.of(modelMax = 1_000_000, current = 128_000)!!

    private fun setting(used: Int? = 40_000, scope: ContextLimitScope = ContextLimitScope.Conversation) =
        ContextLimitSetting(stops, current = 128_000, usedTokens = used, scope = scope)

    @Test
    fun itsStopsRunToTheModelMaxAndItAppliesOnceOnRelease() = runComposeUiTest {
        val applied = mutableListOf<Int>()
        setContent { MaterialTheme { ContextLimitSlider(setting(), onApply = { applied += it }, onCompact = null) } }
        onNodeWithTag(ContextLimitTags.VALUE).assertTextEquals("128k of 1M")
        onNodeWithTag(ContextLimitTags.SLIDER)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "128k of 1M"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Limit")))
        onNodeWithTag(ContextLimitTags.STATUS).assertTextContains("Auto-compacts at 111.6k")
        onNodeWithTag(ContextLimitTags.SLIDER).performSemanticsAction(SemanticsActions.SetProgress) { it((stops.count - 1).toFloat()) }
        waitForIdle()
        assertEquals(listOf(1_000_000), applied)
        onNodeWithTag(ContextLimitTags.VALUE).assertTextEquals("1M of 1M")
    }

    @Test
    fun landingWhereItStartedAppliesNothing() = runComposeUiTest {
        val applied = mutableListOf<Int>()
        setContent { MaterialTheme { ContextLimitSlider(setting(), onApply = { applied += it }, onCompact = null) } }
        onNodeWithTag(ContextLimitTags.SLIDER).performSemanticsAction(SemanticsActions.SetProgress) { it(stops.indexOf(128_000).toFloat()) }
        waitForIdle()
        assertEquals(emptyList(), applied)
    }

    @Test
    fun anArrowKeyMovesOneStop() = runComposeUiTest {
        val applied = mutableListOf<Int>()
        setContent { MaterialTheme { ContextLimitSlider(setting(), onApply = { applied += it }, onCompact = null) } }
        onNodeWithTag(ContextLimitTags.SLIDER).requestFocus().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(listOf(200_000), applied)
    }

    @Test
    fun aLimitBelowUsageWarnsAndOffersCompact() = runComposeUiTest {
        var compacted = 0
        setContent { MaterialTheme { ContextLimitSlider(setting(used = 112_800), onApply = {}, onCompact = { compacted++ }) } }
        onNodeWithTag(ContextLimitTags.STATUS).assertTextContains("Past the auto-compact point", substring = true)
        onNodeWithTag(ContextLimitTags.COMPACT).assertDoesNotExist()
        onNodeWithTag(ContextLimitTags.SLIDER).performSemanticsAction(SemanticsActions.SetProgress) { it(stops.indexOf(64_000).toFloat()) }
        waitForIdle()
        onNodeWithTag(ContextLimitTags.STATUS).assertTextContains("Below the 112.8k already in context", substring = true)
        onNodeWithTag(ContextLimitTags.COMPACT).performClick()
        assertEquals(1, compacted)
    }

    @Test
    fun theScopeLineFollowsLettaCodesRule() = runComposeUiTest {
        setContent { MaterialTheme { ContextLimitSlider(setting(scope = ContextLimitScope.Agent), onApply = {}, onCompact = null) } }
        onNodeWithTag(ContextLimitTags.SCOPE).assertTextEquals(ContextLimitStrings.scope(ContextLimitScope.Agent))
    }

    @Test
    fun theSheetShowsTheReasonWhenTheHostCannotChangeTheLimit() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column {
                    AgentContextSheetContent(
                        model = AgentContextCardModel.present(
                            AgentContextCardInputs("opus", null, ContextMeter.of(112_800, 1_000_000), true, null, compacting = false, turnRunning = false),
                        ),
                        picker = null,
                        onCompact = {},
                        limit = ContextLimitControl.Unsupported,
                    )
                }
            }
        }
        onNodeWithTag(ContextLimitTags.SLIDER).assertDoesNotExist()
        onNodeWithTag(ContextLimitTags.UNSUPPORTED).assertTextEquals(ContextLimitStrings.UNSUPPORTED)
        onNodeWithTag(AgentContextTags.TOTAL).assertTextEquals("112.8k / 1M (11%)")
        onNodeWithTag(AgentContextTags.AUTO_COMPACT_MARK).assertExists()
    }
}
