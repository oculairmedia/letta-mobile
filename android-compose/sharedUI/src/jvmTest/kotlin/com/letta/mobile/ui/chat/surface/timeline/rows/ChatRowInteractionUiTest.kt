@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import com.letta.mobile.ui.chat.surface.ChatPlatformStyle
import com.letta.mobile.ui.chat.surface.LocalChatPlatformStyle
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.letta.mobile.data.chat.tools.ToolStepExpansions
import com.letta.mobile.ui.chat.tools.LocalToolStepExpansions
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import com.letta.mobile.ui.components.LettaListRowDefaults
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.assertTextEquals
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

    /** letta-mobile-zrode: the hover actions (fork, copy) hid the words under them on desktop. */
    @Test
    fun pointerReplyActionsSitBelowTheTextNeverOverIt() = runComposeUiTest {
        val text = "The cache keeps the newest page resident, which goes a long way."
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalChatPlatformStyle provides ChatPlatformStyle.Pointer) {
                    AgentText(AgentTextParams(text = text, isError = false, onFork = {}))
                }
            }
        }
        waitUntil(timeoutMillis = 5_000L) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

        val textBounds = onNodeWithText(text).fetchSemanticsNode().boundsInRoot
        val actions = onNodeWithTag(ChatRowTestTags.AGENT_TEXT_ACTIONS).fetchSemanticsNode().boundsInRoot
        assertTrue(actions.top >= textBounds.bottom, "actions $actions overlap the text $textBounds")
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
        setContent { MaterialTheme { ToolStep(tool, "call-1", rowCallbacks()) } }

        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertExists()
        runOnIdle { tool = tool.copy(status = "completed", result = "done") }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertExists()
    }

    @Test
    fun stepsStartCollapsedAndOnlyAFailureSaysSoOnTheLine() = runComposeUiTest {
        var tool by mutableStateOf(toolCall(status = "completed", result = "done"))
        setContent { MaterialTheme { ToolStep(tool, tool.status.orEmpty(), rowCallbacks()) } }

        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.TOOL_STEP_NOTE, useUnmergedTree = true).assertDoesNotExist()
        runOnIdle { tool = toolCall(status = "failed", result = "boom") }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.TOOL_STEP_NOTE, useUnmergedTree = true).assertTextEquals("failed")
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
        setContent { MaterialTheme { ToolStep(tool, "img-1", rowCallbacks()) } }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertExists()
        onNodeWithTag(ChatRowTestTags.IMAGE_GRID).assertExists()
    }

    @Test
    fun expandingATruncatedToolResultAsksTheOwnerForTheFullBody() = runComposeUiTest {
        val actions = RecordingChatActions()
        val tool = toolCall(status = "completed", result = "preview…").copy(
            resultTruncation = UiToolResultTruncation(messageId = "ret-1", byteLen = 90_000),
        )
        setContent { MaterialTheme { ToolStep(tool, "call-1", rowCallbacks(actions)) } }

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
    fun longPressOnAUserPromptOffersEditAndForkWhenTheOwnerCanBranch() = runComposeUiTest {
        // letta-mobile-bzvro.15 / .16: the prompt's existing message menu carries both actions.
        val actions = RecordingChatActions()
        var capabilities by mutableStateOf(ChatSurfaceCapabilities(fork = true, editAndResend = true))
        val prompt = message("user-3", "user", "Fix the typo")
        setContent {
            MaterialTheme { RenderRow(single(prompt), rowContext(capabilities = capabilities), rowCallbacks(actions)) }
        }

        onNodeWithTag(ChatRowTestTags.USER_PROMPT).performTouchInput { longClick() }
        onNodeWithText("Edit and resend").performClick()
        onNodeWithTag(ChatRowTestTags.USER_PROMPT).performTouchInput { longClick() }
        onNodeWithText("Fork from here").performClick()
        runOnIdle {
            assertEquals(listOf(prompt), actions.edits)
            assertEquals(listOf(prompt), actions.forks)
        }

        runOnIdle { capabilities = ChatSurfaceCapabilities.Default }
        onNodeWithTag(ChatRowTestTags.USER_PROMPT).performTouchInput { longClick() }
        onNodeWithText("Copy").assertExists()
        onNodeWithText("Edit and resend").assertDoesNotExist()
        onNodeWithText("Fork from here").assertDoesNotExist()
    }

    @Test
    fun aPromptStillSendingOffersNeitherEditNorFork() = runComposeUiTest {
        val actions = RecordingChatActions()
        val pending = message("local-1", "user", "Sending…").copy(isPending = true)
        setContent {
            MaterialTheme {
                RenderRow(
                    single(pending),
                    rowContext(capabilities = ChatSurfaceCapabilities(fork = true, editAndResend = true)),
                    rowCallbacks(actions),
                )
            }
        }

        onNodeWithTag(ChatRowTestTags.USER_PROMPT).performTouchInput { longClick() }
        onNodeWithText("Edit and resend").assertDoesNotExist()
        onNodeWithText("Fork from here").assertDoesNotExist()
    }

    @Test
    fun aSettledRunFoldsThroughItsOwnerFromItsHeader() = runComposeUiTest {

        val actions = RecordingChatActions()
        val block = ChatRenderItem.RunBlock(
            runId = "run-1",
            messages = listOf(
                runMessage("a", "Looking into it."),
                runMessage("t", "").copy(toolCalls = listOf(toolCall(status = "success", result = "hello"))),
                runMessage("b", "All done here."),
            ).map { it to GroupPosition.None },
        )
        setContent {
            // letta-mobile-zxk2n.37: folded by default; the page's toggle set opens it.
            MaterialTheme {
                RenderRow(block, rowContext(newestMessageId = "b"), rowCallbacks(actions))
            }
        }

        // Folded, the run shows its prose only: its tool line waits under the summary line.
        awaitText("Looking into it.")
        awaitText("All done here.")
        onNodeWithText("Looking into it.").assertExists()
        onNodeWithText("All done here.").assertExists()
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).assertExists().assert(hasClickAction())
        onNodeWithTag(ChatRowTestTags.RUN_HEADER).performClick()
        runOnIdle { assertEquals(listOf("run-1"), actions.toggledRuns) }
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
        awaitText("Weighing the options")
        onNodeWithText("Weighing the options", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithText("Then picking the cheaper one", substring = true, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.REASONING_TOGGLE).performClick()
        runOnIdle { assertEquals(listOf("r-1"), actions.toggledReasoning) }
        runOnIdle { expanded = setOf("r-1") }
        awaitText("Then picking the cheaper one")
        onNodeWithText("Then picking the cheaper one", substring = true, useUnmergedTree = true).assertExists()
    }

    /**
     * letta-mobile-ckmub.1.3: the reasoning header is one of the run's step rows - the shared list
     * row with the run's status glyph (its spinner while the reasoning streams) - not a row of its own.
     */
    @Test
    fun reasoningHeaderIsARunStepRowOnTheRunsGlyph() = runComposeUiTest {
        var streaming by mutableStateOf(true)
        val thought = UiMessage(
            id = "r-2",
            role = "assistant",
            content = "Comparing the two plans",
            timestamp = "2026-07-19T12:00:00Z",
            isReasoning = true,
        )
        setContent {
            val context = ChatRowContext(
                newestMessageId = thought.id,
                itemState = renderState(),
                capabilities = ChatSurfaceCapabilities.Default,
                streamingMessageId = thought.id.takeIf { streaming },
            )
            MaterialTheme { RenderRow(single(thought), context, rowCallbacks()) }
        }

        awaitText("Thinking")
        onNodeWithTag(ChatRowTestTags.REASONING_GLYPH, useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("Reasoning in progress", useUnmergedTree = true).assertExists()
        onNodeWithTag(ChatRowTestTags.REASONING_TOGGLE).assertIsNotEnabled()
        onNode(
            hasTestTag(LettaListRowDefaults.TitleTestTag) and hasAnyAncestor(hasTestTag(ChatRowTestTags.REASONING_TOGGLE)),
            useUnmergedTree = true,
        ).assertExists()

        runOnIdle { streaming = false }
        awaitText("Thought")
        onNodeWithTag(ChatRowTestTags.REASONING_GLYPH, useUnmergedTree = true).assertExists()
        onNodeWithContentDescription("Reasoning in progress", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.REASONING_TOGGLE).assertHasClickAction()
        onNodeWithContentDescription("Expand reasoning", useUnmergedTree = true).assertExists()
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

    /**
     * letta-mobile-bglj6.1.25: a Bash call under approve-all keeps its (runtime-resolved) request
     * on the message until the tool returns. It is not waiting on the person, so it reads as the
     * plain tool line in every idiom: no "Approval requested", no "requesting input" chip.
     */
    @Test
    fun aRuntimeResolvedApprovalRendersAsItsToolLineOnly() = runComposeUiTest {
        var style by mutableStateOf(ChatPlatformStyle.Touch)
        setContent {
            CompositionLocalProvider(LocalChatPlatformStyle provides style) {
                MaterialTheme { RenderRow(single(runtimeResolvedBashMessage())) }
            }
        }

        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).assertExists()
        onNodeWithText("Approval requested").assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.APPROVAL_TOOL_CALL).assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.APPROVAL_REQUESTING_INPUT, useUnmergedTree = true).assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.SPEAKER_HEADER).assertDoesNotExist()
        runOnIdle { style = ChatPlatformStyle.Pointer }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).assertExists()
        onNodeWithText("Approval requested").assertDoesNotExist()
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
                UiToolCall(
                    name = "Agent",
                    arguments = """{"description":"Audit the build","subagent_type":"general-purpose","prompt":"Look at gradle"}""",
                    result = null,
                    status = "running",
                    toolCallId = "agent-call-1",
                    subagentDispatch = dispatch,
                ),
            ),
        )
        setContent {
            MaterialTheme {
                RenderRow(single(msg), rowContext(), rowCallbacks(openSubagent = { opened = it }))
            }
        }

        // The call reads as its step line; opened, it shows the prompt it gave and a chip to the subagent.
        onNodeWithText("Delegated").assertExists()
        onNodeWithText("to general-purpose · \"Audit the build\"").performClick()
        onNodeWithText("Look at gradle", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithText("Open subagent").performClick()
        runOnIdle { assertEquals(ChatSubagentTarget("agent-call-1", "Audit the build", "agent-sub"), opened) }
    }

    @Test
    fun aLoneToolMessageReadsAsItsStepLinesWithNoSummaryLine() = runComposeUiTest {
        val msg = message("m-1", "assistant", "").copy(toolCalls = listOf(toolCall(status = "success", result = "hello")))
        setContent { MaterialTheme { RenderRow(single(msg), rowContext()) } }

        // The step line itself is the disclosure: its inset opens under it, inside the timeline.
        onNodeWithContentDescription("Show command details").assertDoesNotExist()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertExists()
        val line = onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).fetchSemanticsNode().boundsInRoot
        val body = onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).fetchSemanticsNode().boundsInRoot
        assertTrue(body.top >= line.bottom, "the inset sits under the line ($body vs $line)")
    }

    @Test
    fun anOpenedToolStepSurvivesScrollingAway() = runComposeUiTest {
        val msg = message("m-1", "assistant", "").copy(toolCalls = listOf(toolCall(status = "success", result = "hello")))
        lateinit var focus: FocusManager
        setContent {
            focus = LocalFocusManager.current
            // The timeline (or the shell around it) keeps the steps the person opened.
            CompositionLocalProvider(LocalToolStepExpansions provides remember { ToolStepExpansions() }) {
                MaterialTheme {
                    LazyColumn(Modifier.height(SCROLL_VIEWPORT).testTag(SCROLL_LIST_TAG)) {
                        item(key = "run") { RenderRow(single(msg), rowContext()) }
                        items(FILLER_ROWS) { Box(Modifier.height(SCROLL_VIEWPORT)) }
                    }
                }
            }
        }
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_TOGGLE).performClick()
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertExists()
        // A focused row stays pinned in the list: let go of it first.
        runOnIdle { focus.clearFocus() }
        // Far enough that the row leaves composition, then back.
        onNodeWithTag(SCROLL_LIST_TAG).performScrollToIndex(FILLER_ROWS)
        onNodeWithTag(SCROLL_LIST_TAG).performScrollToIndex(0)
        onNodeWithTag(ChatRowTestTags.TOOL_CARD_BODY).assertExists()
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
    fun clockLabelFormatsInstantsAndLocalTimesAndToleratesGarbage() {
        assertEquals("12:00 PM", messageClockLabel("2026-07-19T12:00:00Z", TimeZone.UTC))
        assertEquals("9:05 AM", messageClockLabel("2026-07-19T09:05:00", TimeZone.UTC))
        assertEquals("2:30 PM", messageClockLabel("2026-07-19T16:30:00+02:00", TimeZone.UTC))
        assertEquals(null, messageClockLabel("", TimeZone.UTC))
        assertEquals(null, messageClockLabel("not a time", TimeZone.UTC))
    }

    @Test
    fun clockLabelFollowsATwentyFourHourClock() {
        // letta-mobile-bglj6.1.23: a 24-hour locale (or the user's toggle) reads "16:30", not "4:30 PM".
        assertEquals("16:30", messageClockLabel("2026-07-19T16:30:00Z", TimeZone.UTC, twentyFourHour = true))
        assertEquals("09:05", messageClockLabel("2026-07-19T09:05:00", TimeZone.UTC, twentyFourHour = true))
        assertEquals(true, localeUses24HourClock(java.util.Locale.GERMANY))
        assertEquals(false, localeUses24HourClock(java.util.Locale.US))
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

    private fun runtimeResolvedBashMessage(): UiMessage {
        val command = """{"command":"curl -s https://example.test/_matrix/client/versions"}"""
        return message("b-1", "assistant", "").copy(
            toolCalls = listOf(UiToolCall(name = "Bash", arguments = command, result = null, status = "running", toolCallId = "bash-1")),
            approvalRequest = UiApprovalRequest(
                requestId = "req-bash-1",
                toolCalls = listOf(UiApprovalToolCall(toolCallId = "bash-1", name = "Bash", arguments = command)),
            ),
        )
    }

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
