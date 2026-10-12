package com.letta.mobile.feature.chat.timeline

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.letta.mobile.data.chat.projection.withPendingApprovalDetails
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.feature.chat.NoOpChatActions
import com.letta.mobile.feature.chat.parity.RowParityFixture
import com.letta.mobile.feature.chat.screen.shared.SharedChatPageLocals
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.chat.surface.composer.ComposerThinkingSnapshot
import com.letta.mobile.ui.chat.surface.timeline.ChatTimelineSnapshot
import com.letta.mobile.ui.theme.LettaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-sieej (P0): the phone's chat over Iroh, Strict mode. The phone's own engine sees the
 * parked Bash call only as a plain tool row; the host's relayed `pending_approvals` snapshot is
 * joined onto it exactly as AdminChatViewModel.present() does ([withPendingApprovalDetails]). The
 * Android timeline (the shared page's rows, Touch idiom, tool details in a sheet) must show the
 * approval card with its controls without a tap, and the composer token must say it waits.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w412dp-h915dp-xhdpi")
class RelayedPendingApprovalTimelineTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun aRelayedPendingBashApprovalIsShownExpandedWithItsControls() {
        val narration = UiMessage(id = "n1", role = "assistant", content = "Let me check the tree.", timestamp = "2026-10-12T10:00:00Z", runId = RUN_ID)
        val bash = UiMessage(
            id = "toolcall-$CALL_ID",
            role = "assistant",
            content = "",
            timestamp = "2026-10-12T10:00:01Z",
            runId = RUN_ID,
            toolCalls = listOf(UiToolCall(name = "Bash", arguments = """{"command":"ls -la"}""", result = null, status = null, toolCallId = CALL_ID)),
        )
        val parked = PendingApprovalDetails(approvalId = "perm-$CALL_ID", toolCallId = CALL_ID, toolName = "Bash")
        val state = RowParityFixture.state(listOf(RowParityFixture.userPrompt, narration, bash), thinking = true)
            .withPendingApprovalDetails(mapOf(CALL_ID to parked))

        // The thinking sweep never stops animating: step the clock by hand.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            LettaTheme(appTheme = AppTheme.LIGHT, themePreset = ThemePreset.DEFAULT, dynamicColor = false) {
                SharedChatPageLocals(hapticsEnabled = false) {
                    Column(Modifier.fillMaxSize()) {
                        ChatTimelineSnapshot(
                            state = state,
                            actions = NoOpChatActions,
                            appearance = ChatSurfaceAppearance(toolDetails = ChatToolDetails.Sheet, platformStyle = ChatPlatformStyle.Touch),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                        ComposerThinkingSnapshot(state, Modifier.fillMaxWidth())
                    }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(SETTLE_MILLIS)

        composeRule.onNodeWithText("Approval requested").assertIsDisplayed()
        composeRule.onNodeWithText("Approve").assertIsDisplayed()
        composeRule.onNodeWithText("Reject").assertIsDisplayed()
        composeRule.onNodeWithText("Approval needed - 1 command").assertIsDisplayed()
        // The run's header says it waits, and so does the composer's token (not "Thinking…").
        composeRule.onNodeWithText("Waiting for your approval").assertIsDisplayed()
        composeRule.onNodeWithText("Thinking", substring = true).assertDoesNotExist()
        composeRule.onAllNodesWithText("Waiting for your approval", substring = true).assertCountEquals(2)
    }

    private companion object {
        const val RUN_ID = "run-strict"
        const val CALL_ID = "call-bash-1"
        const val SETTLE_MILLIS = 1_500L
    }
}
