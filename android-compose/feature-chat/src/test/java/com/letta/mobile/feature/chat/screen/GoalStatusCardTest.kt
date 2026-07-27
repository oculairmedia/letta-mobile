package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.letta.mobile.ui.chat.render.GoalStatusUi
import com.letta.mobile.ui.test.setLettaTestContent
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@Tag("unit")
class GoalStatusCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val callbacks = GoalStatusCallbacks(
        onRefresh = {},
        onContinue = {},
        onPause = {},
        onResume = {},
        onComplete = {},
        onClear = {},
    )

    @Test
    fun `goal-less conversation never composes card while loading`() {
        composeRule.setLettaTestContent(useChatTheme = false) {
            GoalStatusCard(goal = null, loading = true, callbacks = callbacks)
        }

        composeRule.onNodeWithText("Goal").assertDoesNotExist()
        composeRule.onNodeWithText("Refresh").assertDoesNotExist()
        composeRule.onNodeWithText("Loading goal status…").assertDoesNotExist()
    }

    @Test
    fun `positive goal renders card`() {
        composeRule.setLettaTestContent(useChatTheme = false) {
            GoalStatusCard(goal = goal, loading = false, callbacks = callbacks)
        }

        composeRule.onNodeWithText("Goal • active").assertIsDisplayed()
        composeRule.onNodeWithText("Ship the fix").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh").assertIsDisplayed()
    }

    @Test
    fun `refreshing existing goal keeps card mounted`() {
        var loading by mutableStateOf(false)
        composeRule.setLettaTestContent(useChatTheme = false) {
            GoalStatusCard(goal = goal, loading = loading, callbacks = callbacks)
        }
        composeRule.onNodeWithText("Ship the fix").assertIsDisplayed()

        composeRule.runOnIdle { loading = true }

        composeRule.onNodeWithText("Goal").assertIsDisplayed()
        composeRule.onNodeWithText("Ship the fix").assertIsDisplayed()
        composeRule.onNodeWithText("Refresh").assertIsDisplayed()
    }

    private val goal = GoalStatusUi(
        objective = "Ship the fix",
        status = "active",
        activeTimeSeconds = 12,
        tokensUsed = 34,
        tokenBudget = 100,
    )
}
