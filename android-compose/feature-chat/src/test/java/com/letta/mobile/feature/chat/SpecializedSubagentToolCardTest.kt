package com.letta.mobile.feature.chat

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.feature.chat.screen.MessageToolCalls
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class SpecializedSubagentToolCardTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun singleAgentDispatchRendersSpecializedDispatchCard() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    MessageToolCalls(
                        toolCalls = persistentListOf(
                            UiToolCall(
                                name = "Agent",
                                arguments = "{\"description\":\"Investigate restore\"}",
                                result = null,
                                status = "running",
                                toolCallId = "tool-agent-1",
                                subagentDispatch = UiSubagentDispatch(
                                    toolCallId = "tool-agent-1",
                                    description = "Investigate restore",
                                    subagentType = "researcher",
                                    runInBackground = true,
                                    prompt = "look carefully",
                                    taskId = "task-9",
                                ),
                            ),
                        ),
                        messageId = "msg-1",
                    )
                }
            }
        }

        composeRule.onNodeWithText("Dispatched: Investigate restore").assertIsDisplayed()
        composeRule.onNodeWithText("researcher").assertIsDisplayed()
        composeRule.onNodeWithText("background").assertIsDisplayed()
        composeRule.onNodeWithText("running").assertIsDisplayed()
        composeRule.onNodeWithText("task-9").assertIsDisplayed()
        composeRule.onNodeWithText("Show prompt").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("look carefully").assertIsDisplayed()
    }

    @Test
    fun singleTaskNotificationRendersSpecializedNotificationCard() {
        val notification = """
            <task-notification>
                <status>completed</status>
                <summary>Finished research</summary>
                <result>ok</result>
                <agent_id>agent-sub-1</agent_id>
                <tool_call_id>tool-agent-1</tool_call_id>
            </task-notification>
        """.trimIndent()

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    MessageToolCalls(
                        toolCalls = persistentListOf(
                            UiToolCall(
                                name = "Agent",
                                arguments = "{}",
                                result = notification,
                                status = "success",
                                toolCallId = "tool-agent-1",
                            ),
                        ),
                        messageId = "msg-2",
                    )
                }
            }
        }

        composeRule.onNodeWithText("Subagent completed").assertIsDisplayed()
        composeRule.onNodeWithText("Finished research").assertIsDisplayed()
        composeRule.onNodeWithText("completed").assertIsDisplayed()
        composeRule.onNodeWithText("Show full report").assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hide full report").assertIsDisplayed()
        composeRule.onNodeWithText("View conversation").assertIsDisplayed()
    }
}
