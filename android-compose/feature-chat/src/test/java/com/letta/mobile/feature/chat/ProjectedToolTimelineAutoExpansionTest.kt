package com.letta.mobile.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.chat.projection.ToolTimelineCall
import com.letta.mobile.data.chat.projection.ToolTimelineGroup
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.feature.chat.screen.ProjectedToolTimelineGroupCard
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class ProjectedToolTimelineAutoExpansionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun newestRunningCallOwnsAutoExpansionAcrossStaggeredArrival() {
        var groupState by mutableStateOf(timelineGroup(timelineCall("older", "older", ToolTimelineState.Running)))
        composeRule.setContent {
            LettaTheme(AppTheme.LIGHT, ThemePreset.DEFAULT, false) {
                LettaChatTheme {
                    ProjectedToolTimelineGroupCard(listOf(groupState), autoExpandDelayMs = 200L, stagedCollapseDelayMs = 100L)
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(100L)
        composeRule.runOnIdle {
            groupState = timelineGroup(
                timelineCall("older", "older", ToolTimelineState.Running),
                timelineCall("newer", "newer", ToolTimelineState.Running),
            )
        }
        composeRule.mainClock.advanceTimeUntil(timeoutMillis = 1_000L) { isExpanded("Bash(newer)") }
        composeRule.onNodeWithText("Bash(newer)").assertIsDisplayed()
        composeRule.onNodeWithText("Executing older...").assertDoesNotExist()

        composeRule.runOnIdle {
            groupState = timelineGroup(
                timelineCall("older", "older", ToolTimelineState.Succeeded, result = "older done"),
                timelineCall("newer", "newer", ToolTimelineState.Running),
            )
        }
        composeRule.mainClock.advanceTimeBy(300L)
        composeRule.onNodeWithText("older done").assertDoesNotExist()

        composeRule.runOnIdle {
            groupState = timelineGroup(
                timelineCall("older", "older", ToolTimelineState.Succeeded, result = "older done"),
                timelineCall("newer", "newer", ToolTimelineState.Succeeded, result = "newer done"),
            )
        }
        composeRule.mainClock.advanceTimeBy(150L)
        composeRule.onNodeWithText("older done").assertDoesNotExist()
        composeRule.onNodeWithText("newer done").assertDoesNotExist()
    }

    @Test
    fun manualCollapseOverridesAutoExpansion() {
        composeRule.setContent {
            LettaTheme(AppTheme.LIGHT, ThemePreset.DEFAULT, false) {
                LettaChatTheme {
                    ProjectedToolTimelineGroupCard(listOf(timelineGroup(timelineCall("active", "active", ToolTimelineState.Running))), autoExpandDelayMs = 100L)
                }
            }
        }
        composeRule.mainClock.advanceTimeUntil(timeoutMillis = 1_000L) { isExpanded("Bash(active)") }
        composeRule.onNodeWithText("Bash(active)").performClick()
        assertFalse(isExpanded("Bash(active)"))
        composeRule.onNodeWithText("Executing Bash...").assertDoesNotExist()
    }

    @Test
    fun manualOlderExpansionSurvivesNewAutomaticOwner() {
        var groupState by mutableStateOf(timelineGroup(timelineCall("older", "older", ToolTimelineState.Running)))
        composeRule.setContent {
            LettaTheme(AppTheme.LIGHT, ThemePreset.DEFAULT, false) {
                LettaChatTheme {
                    ProjectedToolTimelineGroupCard(listOf(groupState), autoExpandDelayMs = 200L)
                }
            }
        }
        composeRule.onNodeWithText("Bash(older)").performClick()
        composeRule.runOnIdle {
            groupState = timelineGroup(
                timelineCall("older", "older", ToolTimelineState.Running),
                timelineCall("newer", "newer", ToolTimelineState.Running),
            )
        }
        composeRule.mainClock.advanceTimeUntil(timeoutMillis = 1_000L) { isExpanded("Bash(newer)") }
        assertTrue(isExpanded("Bash(older)"))
        assertTrue(isExpanded("Bash(newer)"))
    }

    private fun timelineGroup(vararg calls: ToolTimelineCall) = ToolTimelineGroup(
        key = "group-1",
        calls = calls.toList(),
        state = calls.lastOrNull()?.state ?: ToolTimelineState.Succeeded,
    )

    private fun timelineCall(id: String, command: String, state: ToolTimelineState, result: String? = null) = ToolTimelineCall(
        key = "call:$id",
        toolCallId = id,
        name = "Bash",
        arguments = "{\"command\":\"$command\"}",
        result = result,
        state = state,
        summary = "Bash($command)",
    )

    private fun isExpanded(title: String): Boolean =
        composeRule.onAllNodesWithText(title).fetchSemanticsNodes().any {
            runCatching { it.config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription] }.getOrNull() == "Expanded"
        }
}
