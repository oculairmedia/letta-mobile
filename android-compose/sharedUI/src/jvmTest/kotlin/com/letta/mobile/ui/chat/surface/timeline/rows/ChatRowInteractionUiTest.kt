@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.chat.surface.LocalChatPlatformStyle
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.performTextInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiImageAttachment
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiSubagentDispatch
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.model.UiToolResultTruncation
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.common.GroupPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.TimeZone

/**
 * letta-mobile-bglj6.1: row-level cases ported from desktop's DesktopChatInteractionUiTest,
 * plus the Android row behaviour the shared rows add (message actions, owner-held run and
 * reasoning disclosure, truncated results, approvals, subagents).
 */
class ChatRowInteractionUiTest {
    @Test
    fun assistantMarkdownCanShrinkAfterStreamReconciliation() = runComposeUiTest {
        val longerText = "x".repeat(91)
        val shorterText = "x".repeat(87)
        var markdown by mutableStateOf("**$longerText**")
        setContent { MaterialTheme { AgentText(AgentTextParams(text = markdown, isError = false)) } }

        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithText(longerText).fetchSemanticsNodes().isNotEmpty() }
        runOnIdle { markdown = "**$shorterText**" }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithText(shorterText).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithText(longerText).assertDoesNotExist()
    }

    @Test
    fun assistantCopyActionIsFocusableDiscoverableAndUsablySized() = runComposeUiTest {
        setContent {
            MaterialTheme { RenderRow(single(message("assistant-1", "assistant", "Selectable response"))) }
        }

        val copy = onNodeWithContentDescription("Copy response")
        copy.assertExists().assertHasClickAction()
        copy.performSemanticsAction(SemanticsActions.RequestFocus)
        copy.assertIsFocused()
        val bounds = copy.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= with(density) { 36.dp.toPx() })
        assertTrue(bounds.height >= with(density) { 36.dp.toPx() })
    }

    @Test
    fun userPromptCopyActionStaysDiscoverableWhenHiddenByHoverGating() = runComposeUiTest {
        setContent {
            MaterialTheme { RenderRow(single(message("user-1", "user", "Deploy finished cleanly."))) }
        }

        val copy = onNodeWithContentDescription("Copy message")
        copy.assertExists().assertHasClickAction()
        copy.performSemanticsAction(SemanticsActions.RequestFocus)
        copy.assertIsFocused()
        val bounds = copy.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width >= with(density) { 36.dp.toPx() })
        copy.performClick()
        onNodeWithContentDescription("Copied").assertExists()
    }

    @Test
    fun manualToolDisclosureSurvivesRunningToCompletedRefresh() = runComposeUiTest {
        var tool by mutableStateOf(toolCall(status = "running", result = null))
        setContent { MaterialTheme { ToolCard(tool, "call-1", rowCallbacks()) } }

        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertDoesNotExist()
        runOnIdle { tool = tool.copy(status = "completed", result = "done") }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertDoesNotExist()
    }

    @Test
    fun completedToolsStartCollapsedAndFailuresStartExpanded() = runComposeUiTest {
        var tool by mutableStateOf(toolCall(status = "completed", result = "done"))
        setContent { MaterialTheme { ToolCard(tool, tool.status.orEmpty(), rowCallbacks()) } }

        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertDoesNotExist()
        onNodeWithTag("tool-failure-badge", useUnmergedTree = true).assertDoesNotExist()
        runOnIdle { tool = toolCall(status = "failed", result = "boom") }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertExists()
        onNodeWithTag("tool-failure-badge", useUnmergedTree = true).assertExists()
    }

    @Test
    fun completedImageToolsStartExpanded() = runComposeUiTest {
        val tool = UiToolCall(
            name = "generate_image",
            arguments = "{}",
            result = "ok",
            status = "success",
            toolCallId = "img-1",
            generatedImageAttachments = listOf(UiImageAttachment(base64 = "aaaa", mediaType = "image/png")),
        )
        assertTrue(tool.shouldInitiallyExpand())
        setContent { MaterialTheme { ToolCard(tool, "img-1", rowCallbacks()) } }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertExists()
        onNodeWithTag(ChatRowTestTags.IMAGE_GRID).assertExists()
    }

    @Test
    fun truncatedToolOutputExplainsCopyScopeAndSupportsKeyboardScrolling() = runComposeUiTest {
        val output = (1..41).joinToString("\n") { index -> "line $index ${"x".repeat(120)}" }
        setContent { MaterialTheme { ToolOutputBlock(output) } }

        onNodeWithText("Showing 40 of 41 lines · Copy includes all output").assertExists()
        val scrollable = onNodeWithContentDescription(
            "Tool output. Use left and right arrow keys to scroll horizontally.",
        )
        scrollable.performSemanticsAction(SemanticsActions.RequestFocus)
        scrollable.assertIsFocused().performKeyInput { pressKey(Key.DirectionRight) }
    }

    @Test
    fun expandingATruncatedToolResultAsksTheOwnerForTheFullBody() = runComposeUiTest {
        val actions = RecordingChatActions()
        val tool = toolCall(status = "completed", result = "preview…").copy(
            resultTruncation = UiToolResultTruncation(messageId = "ret-1", byteLen = 90_000),
        )
        setContent { MaterialTheme { ToolCard(tool, "call-1", rowCallbacks(actions)) } }

        runOnIdle { assertTrue(actions.expandedTruncations.isEmpty()) }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_RESULT_PREVIEW, useUnmergedTree = true).assertExists()
        runOnIdle { assertEquals(listOf("ret-1"), actions.expandedTruncations) }
    }

    @Test
    fun longPressOnAUserPromptOffersSendAgainOnlyWhenTheOwnerCanRerun() = runComposeUiTest {
        val actions = RecordingChatActions()
        var capabilities by mutableStateOf(ChatSurfaceCapabilities.Default)
        val prompt = message("user-2", "user", "Run the tests")
        setContent {
            MaterialTheme { RenderRow(single(prompt), rowContext(capabilities = capabilities), rowCallbacks(actions)) }
        }

        onNodeWithTag(ChatRowTestTags.USER_PROMPT).performTouchInput { longClick() }
        onNodeWithText("Send again").performClick()
        runOnIdle { assertEquals(listOf(prompt), actions.reruns) }

        runOnIdle { capabilities = ChatSurfaceCapabilities(rerun = false) }
        onNodeWithTag(ChatRowTestTags.USER_PROMPT).performTouchInput { longClick() }
        onNodeWithText("Copy").assertExists()
        onNodeWithText("Send again").assertDoesNotExist()
    }

    @Test
    fun aSettledRunSummaryIsAPlainLabelThatNeverCollapsesTheRun() = runComposeUiTest {
        val actions = RecordingChatActions()
        val block = ChatRenderItem.RunBlock(
            runId = "run-1",
            messages = listOf(
                runMessage("a", "Looking into it."),
                runMessage("b", "All done here."),
            ).map { it to GroupPosition.None },
        )
        setContent {
            // A stale collapsed id from the owner no longer hides the run's steps: the run has no
            // collapse of its own (letta-mobile-bglj6.1.11).
            MaterialTheme {
                RenderRow(
                    block,
                    rowContext(itemState = renderState(collapsedRunIds = setOf("run-1")), newestMessageId = "b"),
                    rowCallbacks(actions),
                )
            }
        }

        onNodeWithTag(ChatRowTestTags.RUN_HEADER).assertExists().assert(hasClickAction().not())
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).performClick()
        runOnIdle { assertEquals(emptyList(), actions.toggledRuns) }
        onNodeWithText("Looking into it.").assertExists()
        onNodeWithText("All done here.").assertExists()
    }

    @Test
    fun reasoningDisclosureIsOwnerState() = runComposeUiTest {
        val actions = RecordingChatActions()
        var expanded by mutableStateOf(emptySet<String>())
        val thought = UiMessage(
            id = "r-1",
            role = "assistant",
            content = "Weighing the options\n\nThen picking the cheaper one",
            timestamp = "2026-07-19T12:00:00Z",
            isReasoning = true,
        )
        setContent {
            MaterialTheme { RenderRow(single(thought), rowContext(itemState = renderState(expandedReasoning = expanded)), rowCallbacks(actions)) }
        }

        // Collapsed, the header previews only the reasoning's first line.
        onNodeWithText("Weighing the options", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithText("Then picking the cheaper one", substring = true, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.REASONING_TOGGLE).performClick()
        runOnIdle { assertEquals(listOf("r-1"), actions.toggledReasoning) }
        runOnIdle { expanded = setOf("r-1") }
        onNodeWithText("Then picking the cheaper one", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun askUserQuestionAnswersThroughSubmitApproval() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent { MaterialTheme { RenderRow(single(questionMessage()), rowContext(), rowCallbacks(actions)) } }

        onNodeWithText("Kotlin").performClick()
        onNodeWithText("Send answer").performClick()
        runOnIdle {
            val approval = actions.approvals.single()
            assertEquals("req-1", approval.requestId)
            assertEquals(listOf("ask-1"), approval.toolCallIds)
            assertTrue(approval.approve)
            assertTrue(approval.reason.orEmpty().contains("Kotlin"))
        }
    }

    @Test
    fun approvalControlsAreDisabledWhenTheOwnerCannotTakeApprovals() = runComposeUiTest {
        setContent {
            MaterialTheme {
                RenderRow(single(questionMessage()), rowContext(capabilities = ChatSurfaceCapabilities(approvals = false)))
            }
        }

        onNodeWithText("Dismiss").assertIsNotEnabled()
        onNodeWithText("Send answer").assertIsNotEnabled()
    }

    /** letta-mobile-bglj6.1.22: the reason is typed in a focused modal, not an inline field. */
    @Test
    fun rejectingAsksForTheReasonInAModalDialog() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent { MaterialTheme { RenderRow(single(genericApprovalMessage()), rowContext(), rowCallbacks(actions)) } }

        onNodeWithTag(ChatRowTestTags.APPROVAL_REASON).assertDoesNotExist()
        onNodeWithText("Reject").performClick()
        onNodeWithTag(ChatRowTestTags.APPROVAL_REJECT_DIALOG).assertExists()
        onNodeWithTag(ChatRowTestTags.APPROVAL_REASON).performTextInput("Not this file")
        onNodeWithTag(ChatRowTestTags.APPROVAL_REJECT_CONFIRM).performClick()
        onNodeWithTag(ChatRowTestTags.APPROVAL_REJECT_DIALOG).assertDoesNotExist()
        runOnIdle {
            val decision = actions.approvals.single()
            assertEquals("req-2", decision.requestId)
            assertEquals(listOf("ask-2"), decision.toolCallIds)
            assertEquals(false, decision.approve)
            assertEquals("Not this file", decision.reason)
        }
    }

    @Test
    fun cancellingTheRejectDialogLeavesTheRequestPending() = runComposeUiTest {
        val actions = RecordingChatActions()
        setContent { MaterialTheme { RenderRow(single(genericApprovalMessage()), rowContext(), rowCallbacks(actions)) } }

        onNodeWithText("Reject").performClick()
        onNodeWithText("Cancel").performClick()
        onNodeWithTag(ChatRowTestTags.APPROVAL_REJECT_DIALOG).assertDoesNotExist()
        runOnIdle { assertTrue(actions.approvals.isEmpty()) }
    }

    @Test
    fun onTouchTheRequestedCallsWearAndroidsToolCardChrome() = runComposeUiTest {
        var style by mutableStateOf(ChatPlatformStyle.Touch)
        setContent {
            CompositionLocalProvider(LocalChatPlatformStyle provides style) {
                MaterialTheme { RenderRow(single(genericApprovalMessage())) }
            }
        }

        onNodeWithTag(ChatRowTestTags.APPROVAL_TOOL_CALL).assertExists()
        onNodeWithTag(ChatRowTestTags.APPROVAL_REQUESTING_INPUT, useUnmergedTree = true).assertExists()
        onNodeWithText("requesting input", useUnmergedTree = true).assertExists()
        runOnIdle { style = ChatPlatformStyle.Pointer }
        onNodeWithTag(ChatRowTestTags.APPROVAL_TOOL_CALL).assertDoesNotExist()
        onNodeWithText("requesting input", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun subagentDispatchOpensTheSubagentThroughTheHost() = runComposeUiTest {
        var opened: ChatSubagentTarget? = null
        val dispatch = UiSubagentDispatch(
            toolCallId = "agent-call-1",
            description = "Audit the build",
            subagentType = "general-purpose",
            runInBackground = false,
            prompt = "Look at gradle",
            subagentAgentId = "agent-sub",
        )
        val msg = message("m-1", "assistant", "").copy(
            toolCalls = listOf(
                UiToolCall(name = "Agent", arguments = "{}", result = null, status = "running", subagentDispatch = dispatch),
            ),
        )
        setContent {
            MaterialTheme {
                RenderRow(single(msg), rowContext(toolDetails = ChatToolDetails.Sheet), rowCallbacks(openSubagent = { opened = it }))
            }
        }

        // The call reads as one summary line; on a touch host its cards open in a sheet.
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_DETAILS).assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_INLINE).assertDoesNotExist()
        onNodeWithText("Dispatched: Audit the build").performClick()
        runOnIdle { assertEquals(ChatSubagentTarget("agent-call-1", "Audit the build", "agent-sub"), opened) }
        onNodeWithText("Show prompt").performClick()
        onNodeWithText("Look at gradle").assertExists()
    }

    @Test
    fun toolSummaryExpandsItsCardsInPlaceOnAPointerHost() = runComposeUiTest {
        val msg = message("m-1", "assistant", "").copy(toolCalls = listOf(toolCall(status = "success", result = "hello")))
        setContent { MaterialTheme { RenderRow(single(msg), rowContext(toolDetails = ChatToolDetails.Inline)) } }

        onNodeWithTag(ChatRowTestTags.TOOL_RUN_INLINE).assertDoesNotExist()
        onNodeWithContentDescription("Show command details").assertExists()

        // A disclosure, not a sheet: the cards open under the line, inside the timeline.
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_INLINE).assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_DETAILS).assertDoesNotExist()
        onNodeWithContentDescription("Hide command details").assertExists()
        val line = onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).fetchSemanticsNode().boundsInRoot
        val cards = onNodeWithTag(ChatRowTestTags.TOOL_RUN_INLINE).fetchSemanticsNode().boundsInRoot
        assertTrue(cards.top >= line.bottom, "the cards sit under the line ($cards vs $line)")

        // Tapping the line again folds them away.
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_INLINE).assertDoesNotExist()
    }

    @Test
    fun anOpenedToolDisclosureSurvivesScrollingAway() = runComposeUiTest {
        val msg = message("m-1", "assistant", "").copy(toolCalls = listOf(toolCall(status = "success", result = "hello")))
        lateinit var focus: FocusManager
        setContent {
            focus = LocalFocusManager.current
            MaterialTheme {
                LazyColumn(Modifier.height(SCROLL_VIEWPORT).testTag(SCROLL_LIST_TAG)) {
                    item(key = "run") { RenderRow(single(msg), rowContext(toolDetails = ChatToolDetails.Inline)) }
                    items(FILLER_ROWS) { Box(Modifier.height(SCROLL_VIEWPORT)) }
                }
            }
        }
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_INLINE).assertExists()
        // A focused row stays pinned in the list: let go of it first.
        runOnIdle { focus.clearFocus() }
        // Far enough that the row leaves composition, then back.
        onNodeWithTag(SCROLL_LIST_TAG).performScrollToIndex(FILLER_ROWS)
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).assertDoesNotExist()
        onNodeWithTag(SCROLL_LIST_TAG).performScrollToIndex(0)
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_INLINE).assertExists()
    }

    @Test
    fun aRunDurationJustShortOfAMinuteRoundsToAMinute() = runComposeUiTest {
        var almost = ""
        var minute = ""
        var under = ""
        setContent {
            almost = formatRunDuration(59_950L)
            minute = formatRunDuration(60_000L)
            under = formatRunDuration(59_940L)
        }
        waitForIdle()
        assertEquals(minute, almost, "59,950 ms reads as a minute, not \"60.0s\"")
        assertTrue(under.startsWith("59.9"), under)
    }

    @Test
    fun toolSummaryOpensASheetOnATouchHost() = runComposeUiTest {
        val msg = message("m-1", "assistant", "").copy(toolCalls = listOf(toolCall(status = "success", result = "hello")))
        setContent { MaterialTheme { RenderRow(single(msg), rowContext(toolDetails = ChatToolDetails.Sheet)) } }

        onNodeWithContentDescription("Open command details").assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_SUMMARY).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_DETAILS).assertExists()
        onNodeWithTag(ChatRowTestTags.TOOL_RUN_INLINE).assertDoesNotExist()
    }

    @Test
    fun clockLabelFormatsInstantsAndLocalTimesAndToleratesGarbage() {
        assertEquals("12:00 PM", messageClockLabel("2026-07-19T12:00:00Z", TimeZone.UTC))
        assertEquals("9:05 AM", messageClockLabel("2026-07-19T09:05:00", TimeZone.UTC))
        assertEquals("2:30 PM", messageClockLabel("2026-07-19T16:30:00+02:00", TimeZone.UTC))
        assertEquals(null, messageClockLabel("", TimeZone.UTC))
        assertEquals(null, messageClockLabel("not a time", TimeZone.UTC))
    }

    private companion object {
        val SCROLL_VIEWPORT = 400.dp
        const val FILLER_ROWS = 30
        const val SCROLL_LIST_TAG = "scroll-list"
    }

    private fun toolCall(status: String, result: String?) = UiToolCall(
        name = "shell",
        arguments = "echo hello",
        result = result,
        status = status,
        toolCallId = "call-1",
    )

    private fun runMessage(id: String, content: String) = UiMessage(
        id = id,
        role = "assistant",
        content = content,
        timestamp = "2026-07-19T12:00:00Z",
        runId = "run-1",
    )

    /** A user-input tool whose arguments are not a question spec: the generic approve/reject card. */
    private fun genericApprovalMessage() = message("q-2", "assistant", "").copy(
        approvalRequest = UiApprovalRequest(
            requestId = "req-2",
            toolCalls = listOf(UiApprovalToolCall(toolCallId = "ask-2", name = "AskUserQuestion", arguments = "rm -rf build")),
        ),
    )

    private fun questionMessage() = message("q-1", "assistant", "").copy(
        approvalRequest = UiApprovalRequest(
            requestId = "req-1",
            toolCalls = listOf(
                UiApprovalToolCall(
                    toolCallId = "ask-1",
                    name = "AskUserQuestion",
                    arguments = """{"questions":[{"question":"Which language?","options":[{"label":"Kotlin"},{"label":"Rust"}]}]}""",
                ),
            ),
        ),
    )
}

internal fun single(message: UiMessage): ChatRenderItem = ChatRenderItem.Single(message, GroupPosition.None)

@androidx.compose.runtime.Composable
internal fun RenderRow(
    item: ChatRenderItem,
    context: ChatRowContext = rowContext(),
    callbacks: ChatRowCallbacks = rowCallbacks(),
) = ChatRenderItemRow(item = item, context = context, callbacks = callbacks)
