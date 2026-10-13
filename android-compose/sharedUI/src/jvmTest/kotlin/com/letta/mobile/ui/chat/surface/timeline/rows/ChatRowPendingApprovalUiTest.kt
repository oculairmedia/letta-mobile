@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.withPendingApprovalDetails
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.chat.surface.LocalChatPlatformStyle
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import com.letta.mobile.ui.common.GroupPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-sieej (P0): a Bash call parked for approval under "Strict"/"Ask" drew on the phone
 * (and on desktop) only as a collapsed run row, "Worked · 1 tool / Running Bash - 1 command", with
 * the card folded into the tool line's sheet/disclosure. A pending approval must be impossible to
 * miss: the run stays open, its header says it waits, the line says "Approval needed", and the card
 * with Approve / Reject shows inline on every host. Only once answered does the run fold back.
 */
class ChatRowPendingApprovalUiTest {
    @Test
    fun aRelayedPendingApprovalShowsItsCardExpandedOnATouchHost() = runComposeUiTest {
        val actions = RecordingChatActions()
        showRun(relayedRun(parked = true), RowHost.Phone, actions)

        assertCardShownAndActionable(actions)
    }

    @Test
    fun aPendingApprovalShowsItsCardExpandedOnADesktopHost() = runComposeUiTest {
        val actions = RecordingChatActions()
        showRun(relayedRun(parked = true), RowHost.Desktop, actions)

        assertCardShownAndActionable(actions)
    }

    @Test
    fun aPendingApprovalOpensARunTheOwnerHadFolded() = runComposeUiTest {
        // The page's fold (collapsedRunIds) holds the run, yet a pending request keeps it open.
        showRun(relayedRun(parked = true), RowHost.Phone, collapsed = true)

        onNodeWithText("Approval requested").assertIsDisplayed()
        onNodeWithText("Approve").assertIsDisplayed()
        onNodeWithText("Waiting for your approval").assertIsDisplayed()
    }

    @Test
    fun onceAnsweredTheRunFoldsBackToItsLegacySummary() = runComposeUiTest {
        var item by mutableStateOf(relayedRun(parked = true))
        setContent {
            CompositionLocalProvider(LocalChatPlatformStyle provides ChatPlatformStyle.Touch) {
                MaterialTheme {
                    RenderRow(item, rowContext(itemState = renderState(collapsedRunIds = setOf(RUN_ID)), toolDetails = ChatToolDetails.Sheet))
                }
            }
        }
        onNodeWithText("Approval requested").assertIsDisplayed()

        // The host resolved the request (its relayed details are gone) and the tool returned.
        item = relayedRun(parked = false, result = "ok")
        waitForIdle()
        onNodeWithText("Approval requested").assertDoesNotExist()
        onNodeWithText("Waiting for your approval").assertDoesNotExist()
        onNodeWithText("Approve").assertDoesNotExist()
    }

    @Test
    fun aRuntimeResolvedBashStillReadsAsItsPlainLine() = runComposeUiTest {
        // Approve-all: nothing parked, so no card and no "waiting" header (PR #1805's rule).
        showRun(relayedRun(parked = false), RowHost.Phone)

        onNodeWithText("Approval requested").assertDoesNotExist()
        onNodeWithText("Waiting for your approval").assertDoesNotExist()
        // letta-mobile-zxk2n.37: nothing holds it open, so it folds under its one summary line.
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).assertDoesNotExist()
    }

    private fun ComposeUiTest.assertCardShownAndActionable(actions: RecordingChatActions) {
        // No tap on the line first: the card is already there, under it.
        onNodeWithText("Waiting for your approval").assertIsDisplayed()
        // ckmub.1.2: the parked call is a step row straight under the line, its glyph saying it waits.
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).assertIsDisplayed()
        onNodeWithContentDescription("Awaiting approval", useUnmergedTree = true).assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).assertDoesNotExist()
        onNodeWithText("Approval requested").assertIsDisplayed()
        onNodeWithText("Reject").assertIsDisplayed()
        onNodeWithText("Always allow Bash(ls:*)").assertIsDisplayed()
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_DETAILS).assertDoesNotExist()

        onNodeWithText("Approve").performClick()
        runOnIdle {
            val decision = actions.approvals.single()
            assertEquals(APPROVAL_ID, decision.requestId)
            assertEquals(listOf(CALL_ID), decision.toolCallIds)
            assertTrue(decision.approve)
        }
    }

    private fun ComposeUiTest.showRun(
        item: ChatRenderItem,
        host: RowHost,
        actions: RecordingChatActions = RecordingChatActions(),
        collapsed: Boolean = false,
    ) {
        val state = renderState(collapsedRunIds = if (collapsed) setOf(RUN_ID) else emptySet())
        setContent {
            CompositionLocalProvider(LocalChatPlatformStyle provides host.style) {
                MaterialTheme { RenderRow(item, rowContext(itemState = state, toolDetails = host.details), rowCallbacks(actions)) }
            }
        }
    }

    /**
     * The phone's view of a Strict-mode turn over Iroh: narration, then the Bash call as a plain tool
     * row (its own engine never sees the host's control request), joined to the host's relayed
     * `pending_approvals` snapshot by [withPendingApprovalDetails], as AdminChatViewModel does.
     */
    private fun relayedRun(parked: Boolean, result: String? = null): ChatRenderItem {
        val narration = UiMessage(id = "n", role = "assistant", content = "Let me look.", timestamp = "2026-07-19T12:00:00Z", runId = RUN_ID)
        val bash = UiMessage(
            id = "toolcall-$CALL_ID",
            role = "assistant",
            content = "",
            timestamp = "2026-07-19T12:00:01Z",
            runId = RUN_ID,
            toolCalls = listOf(
                UiToolCall(
                    name = "Bash",
                    arguments = """{"command":"ls"}""",
                    result = result,
                    status = if (result != null) "success" else null,
                    toolCallId = CALL_ID,
                ),
            ),
        )
        val parkedDetails = if (parked) mapOf(CALL_ID to PARKED) else emptyMap()
        val messages = withPendingApprovalDetails(listOf(narration, bash), parkedDetails)
        return ChatRenderItem.RunBlock(runId = RUN_ID, messages = messages.map { it to GroupPosition.None })
    }

    /** How a host draws the row: the phone (Touch, details in a sheet) or desktop (Pointer, inline). */
    private enum class RowHost(val style: ChatPlatformStyle, val details: ChatToolDetails) {
        Phone(ChatPlatformStyle.Touch, ChatToolDetails.Sheet),
        Desktop(ChatPlatformStyle.Pointer, ChatToolDetails.Inline),
    }

    private companion object {
        const val RUN_ID = "run-strict"
        const val CALL_ID = "call-bash"
        const val APPROVAL_ID = "perm-call-bash"
        val PARKED = PendingApprovalDetails(
            approvalId = APPROVAL_ID,
            toolCallId = CALL_ID,
            toolName = "Bash",
            suggestions = listOf(com.letta.mobile.runtime.PermissionSuggestion("allow-ls", "Bash(ls:*)")),
        )
    }
}
