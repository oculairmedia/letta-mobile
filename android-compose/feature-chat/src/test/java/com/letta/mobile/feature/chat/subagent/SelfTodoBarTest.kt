package com.letta.mobile.feature.chat.subagent

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import com.letta.mobile.ui.test.setLettaTestContent
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-vk7kc: test coverage for self-todo wiring in ActiveSubagentBar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class SelfTodoBarTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selfTodoChipIsRenderedCorrectlyAndDistinctly() {
        val selfSubagent = ActiveSubagent(
            id = ActiveSubagent.SELF_ID,
            description = "Main agent plan",
            subagentType = "general",
            status = ActiveSubagent.Status.RUNNING,
            isSelf = true
        )

        val regularSubagent = ActiveSubagent(
            id = "task_123",
            description = "Worker agent task",
            subagentType = "general",
            status = ActiveSubagent.Status.RUNNING,
            isSelf = false
        )

        composeRule.setLettaTestContent {
            ActiveSubagentBar(subagents = persistentListOf(selfSubagent, regularSubagent))
        }

        // Verify the self-todo chip renders properly with its specific "Your plan:" prefix
        composeRule.onNodeWithText("Main agent plan").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Your plan: Main agent plan").assertIsDisplayed()

        // Verify the regular subagent renders correctly and isn't overwritten by the self entry
        composeRule.onNodeWithText("Worker agent task").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Subagent running: Worker agent task").assertIsDisplayed()
    }
}
