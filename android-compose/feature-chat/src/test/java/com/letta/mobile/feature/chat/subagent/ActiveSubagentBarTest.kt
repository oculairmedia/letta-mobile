package com.letta.mobile.feature.chat.subagent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.ui.test.setLettaTestContent
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-73o2h.2: Compose coverage for the active-subagent status bar.
 * Verifies the active-only visibility rule end-to-end:
 *  - empty -> hidden (no chips, no content)
 *  - single -> one chip with description + spinner
 *  - multiple (> threshold) -> condensed count form
 *  - live transition to empty hides the bar
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class ActiveSubagentBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun running(id: String, description: String) = ActiveSubagent(
        id = id,
        description = description,
        subagentType = "general",
        status = ActiveSubagent.Status.RUNNING,
    )

    private fun terminal(
        id: String,
        description: String,
        status: ActiveSubagent.Status,
        subagentAgentId: String? = null,
    ) = ActiveSubagent(
        id = id,
        description = description,
        subagentType = "general",
        status = status,
        subagentAgentId = subagentAgentId,
        terminalAt = 0L,
    )

    @Test
    fun emptyListHidesTheBar() {
        composeRule.setLettaTestContent {
            ActiveSubagentBar(subagents = persistentListOf())
        }

        // No chip semantics at all.
        composeRule.onAllNodesWithContentDescription("Running subagent:", substring = true)
            .assertCountEquals(0)
    }

    @Test
    fun singleActiveShowsOneChipWithDescription() {
        composeRule.setLettaTestContent {
            ActiveSubagentBar(
                subagents = persistentListOf(running("task_1", "sentinel-single-desc")),
            )
        }

        composeRule.onNodeWithText("sentinel-single-desc").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Running subagent: sentinel-single-desc")
            .assertIsDisplayed()
    }

    @Test
    fun twoActiveStillShowsIndividualChips() {
        composeRule.setLettaTestContent {
            ActiveSubagentBar(
                subagents = persistentListOf(
                    running("task_1", "alpha-desc"),
                    running("task_2", "beta-desc"),
                ),
            )
        }

        composeRule.onNodeWithText("alpha-desc").assertIsDisplayed()
        composeRule.onNodeWithText("beta-desc").assertIsDisplayed()
        // Not condensed at the threshold boundary.
        composeRule.onAllNodesWithText("subagents running", substring = true).assertCountEquals(0)
    }

    @Test
    fun manyActiveCondensesToCountForm() {
        composeRule.setLettaTestContent {
            ActiveSubagentBar(
                subagents = (1..4).map { running("task_$it", "desc-$it") }.toImmutableList(),
            )
        }

        composeRule.onNodeWithText("4 subagents running").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("4 subagents running").assertIsDisplayed()
        // Individual descriptions are NOT rendered in condensed form.
        composeRule.onAllNodesWithText("desc-1").assertCountEquals(0)
    }

    @Test
    fun completedSubagentShowsReviewableTerminalChip() {
        // letta-mobile-29h9u: a lingering completed chip renders its outcome
        // (not the running spinner) so the user can review success.
        composeRule.setLettaTestContent {
            ActiveSubagentBar(
                subagents = persistentListOf(
                    terminal("done_1", "winning-desc", ActiveSubagent.Status.COMPLETED),
                ),
            )
        }

        composeRule.onNodeWithText("winning-desc").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Completed subagent: winning-desc")
            .assertIsDisplayed()
        // It is NOT labeled as a running chip.
        composeRule.onAllNodesWithContentDescription("Running subagent: winning-desc")
            .assertCountEquals(0)
    }

    @Test
    fun failedSubagentShowsFailedTerminalChip() {
        composeRule.setLettaTestContent {
            ActiveSubagentBar(
                subagents = persistentListOf(
                    terminal("fail_1", "losing-desc", ActiveSubagent.Status.FAILED),
                ),
            )
        }

        composeRule.onNodeWithContentDescription("Failed subagent: losing-desc")
            .assertIsDisplayed()
    }

    @Test
    fun viewConversationAffordanceShownAndFiresWhenCorrelated() {
        // letta-mobile-vo9y1: a chip with a known agent id exposes the
        // view-conversation affordance and invokes the callback.
        var viewed: String? = null
        composeRule.setLettaTestContent {
            ActiveSubagentBar(
                subagents = persistentListOf(
                    terminal(
                        "done_2",
                        "review-me-desc",
                        ActiveSubagent.Status.COMPLETED,
                        subagentAgentId = "agent-local-xyz",
                    ),
                ),
                onViewConversation = { viewed = it.subagentAgentId },
            )
        }

        composeRule.onNodeWithContentDescription("View conversation: review-me-desc")
            .assertIsDisplayed()
            .performClick()
        composeRule.runOnIdle {
            org.junit.Assert.assertEquals("agent-local-xyz", viewed)
        }
    }

    @Test
    fun viewConversationAffordanceHiddenWhenNoAgentId() {
        composeRule.setLettaTestContent {
            ActiveSubagentBar(
                subagents = persistentListOf(running("task_1", "no-nav-desc")),
            )
        }

        composeRule.onAllNodesWithContentDescription("View conversation:", substring = true)
            .assertCountEquals(0)
    }

    @Test
    fun transitionToEmptyHidesTheBar() {
        var subagents: ImmutableList<ActiveSubagent> by mutableStateOf(
            persistentListOf(running("task_1", "vanishing-desc")),
        )

        composeRule.setLettaTestContent {
            ActiveSubagentBar(subagents = subagents)
        }

        composeRule.onNodeWithText("vanishing-desc").assertIsDisplayed()

        composeRule.runOnIdle { subagents = persistentListOf() }

        composeRule.onAllNodesWithText("vanishing-desc").assertCountEquals(0)
    }
}
