package com.letta.mobile.feature.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.feature.chat.screen.RunActivityDisclosureTestTags
import com.letta.mobile.feature.chat.screen.RunBlock
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-ah1ng render coverage: an auto-collapsed completed run hides
 * its tool/reasoning rows while the run disclosure stays tappable so the user
 * can expand the work again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class RunBlockCollapsedDisclosureTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun collapsedCompletedRunHidesToolRowsButKeepsDisclosureTappable() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    RunBlock(
                        messages = listOf(
                            message(id = "reasoning-1", content = "Inspecting the request", isReasoning = true),
                            toolMessage(id = "tool-1", command = "collapse-check"),
                            message(id = "final-1", content = "All done."),
                        ),
                        collapsed = true,
                        onToggleCollapsed = {},
                        showCompletedDisclosure = true,
                    ) { message, _, rowModifier ->
                        Box(
                            modifier = rowModifier.testTag("run-row-${message.id}"),
                        ) {
                            Text(text = message.content.ifBlank { message.id })
                        }
                    }
                }
            }
        }

        // Collapsed: only the final-outcome preview renders; reasoning and
        // tool rows are hidden.
        composeRule.onNodeWithText("Bash(collapse-check)").assertDoesNotExist()
        composeRule.onNodeWithTag("run-row-reasoning-1").assertDoesNotExist()
        composeRule.onNodeWithTag("run-row-final-1").assertIsDisplayed()

        // The completed-run disclosure remains rendered and tappable…
        composeRule.onNodeWithTag(RunActivityDisclosureTestTags.Header)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        composeRule.waitForIdle()

        // …and tapping it expands the previously hidden work.
        composeRule.onNodeWithText("Bash(collapse-check)").assertIsDisplayed()
        composeRule.onNodeWithTag("run-row-reasoning-1").assertIsDisplayed()
    }

    @Test
    fun activeRunIgnoresCollapseAndDisclosureStaysInWorkingMode() {
        var toggles = 0
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    RunBlock(
                        messages = listOf(
                            message(id = "reasoning-1", content = "Working step", isReasoning = true),
                            message(id = "pending-1", content = "Streaming tail", isPending = true),
                        ),
                        collapsed = true,
                        onToggleCollapsed = { toggles++ },
                        isStreaming = true,
                    ) { message, _, rowModifier ->
                        Box(modifier = rowModifier.testTag("run-row-${message.id}")) {
                            Text(text = message.content)
                        }
                    }
                }
            }
        }

        // Active runs must stay expanded regardless of a stale collapse flag.
        composeRule.onNodeWithTag("run-row-reasoning-1").assertIsDisplayed()
        composeRule.onNodeWithTag("run-row-pending-1").assertIsDisplayed()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(0, toggles) }
    }

    private fun message(
        id: String,
        content: String,
        isPending: Boolean = false,
        isReasoning: Boolean = false,
    ) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = "2026-05-16T00:00:00Z",
        runId = "run-1",
        stepId = id,
        isPending = isPending,
        isReasoning = isReasoning,
    )

    private fun toolMessage(id: String, command: String) = UiMessage(
        id = id,
        role = "assistant",
        content = "",
        timestamp = "2026-05-16T00:00:01Z",
        runId = "run-1",
        stepId = id,
        toolCalls = listOf(
            UiToolCall(
                name = "Bash",
                arguments = """{"command":"$command"}""",
                result = "ok",
                status = "success",
                toolCallId = "call-$id",
            ),
        ),
    )
}
