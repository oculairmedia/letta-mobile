package com.letta.mobile.feature.chat.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.ui.theme.LettaChatTheme
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// letta-mobile-vilsn / letta-mobile-jbui1: ApprovalRequestControls must disappear as
// soon as the underlying UiApprovalRequest clears. The previous implementation latched
// the last non-null request in `remember { mutableStateOf(approval) }` and never
// reset it, so an approval that resolved server-side stayed visible forever ("this
// is what happens if I come back to a running conversation I end up seeing the
// approval UI that I should not be seeing"). These tests pin the content-driven
// behaviour: the controls render iff the request is non-null.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ApprovalRequestControlsTest {
    @Test
    fun ordinaryAutoAllowedRequestRendersNoApprovalChrome() {
        val request = UiApprovalRequest(
            requestId = "auto-allowed",
            toolCalls = listOf(
                UiApprovalToolCall(
                    toolCallId = "call-1",
                    name = "Bash",
                    arguments = """{"command":"pwd"}""",
                ),
            ),
        )

        composeRule.setContent {
            LettaTheme(AppTheme.LIGHT, ThemePreset.DEFAULT, false) {
                ApprovalRequestControls(
                    approval = request,
                    isSubmitting = false,
                    onDecision = { _, _, _, _ -> error("ordinary calls must not expose approval actions") },
                )
            }
        }

        composeRule.onNodeWithText("Review the requested tool actions before continuing.").assertDoesNotExist()
        composeRule.onNodeWithText("Reject").assertDoesNotExist()
        composeRule.onNodeWithText("Approve").assertDoesNotExist()
    }

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun controlsAppearWhenApprovalRequestIsPresent() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    ApprovalRequestControls(
                        approval = sampleApproval(),
                        isSubmitting = false,
                        onDecision = { _, _, _, _ -> },
                    )
                }
            }
        }
        composeRule.onNodeWithText("The agent has a question").assertIsDisplayed()
        composeRule.onNodeWithText("Continue?").assertIsDisplayed()
    }

    @Test
    fun controlsDisappearWhenApprovalRequestClears() {
        var approval by mutableStateOf<UiApprovalRequest?>(sampleApproval())

        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                LettaChatTheme {
                    ApprovalRequestControls(
                        approval = approval,
                        isSubmitting = false,
                        onDecision = { _, _, _, _ -> },
                    )
                }
            }
        }

        // Sanity: while the request is present the controls are on screen.
        composeRule.onNodeWithText("The agent has a question").assertIsDisplayed()

        // Simulate the server-side decision: the request clears from the message.
        composeRule.runOnUiThread { approval = null }
        composeRule.waitForIdle()

        // The latch regression: this used to stay rendered because `rememberedApproval`
        // held the previous non-null request forever.
        composeRule.onNodeWithText("The agent has a question").assertDoesNotExist()
        composeRule.onNodeWithText("Continue?").assertDoesNotExist()
    }

    private fun sampleApproval() = UiApprovalRequest(
        requestId = "approval-1",
        toolCalls = listOf(
            UiApprovalToolCall(
                toolCallId = "call-a",
                name = "AskUserQuestion",
                arguments = """{"questions":[{"question":"Continue?","options":[{"label":"Yes"}]}]}""",
            ),
        ),
    )
}
