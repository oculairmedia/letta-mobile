@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.ui.chat.branch.ForkNameDialogTags
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-7mys7: fork, edit-and-resend and rerun on the shared rows, and the fork's name dialog. */
class ChatRowBranchActionsUiTest {
    private val branching = ChatSurfaceCapabilities(fork = true, editAndResend = true, rerunInFork = true)

    @Test
    fun aPromptOffersRerunInANewBranchWhenTheOwnerCanBranch() = runComposeUiTest {
        val actions = RecordingChatActions()
        val prompt = message("user-1", "user", "Plan the trip")
        setContent { MaterialTheme { RenderRow(single(prompt), rowContext(capabilities = branching), rowCallbacks(actions)) } }

        onNodeWithTag(ChatRowTestTags.USER_PROMPT).performTouchInput { longClick() }
        onNodeWithText("Rerun in a new branch").performClick()

        runOnIdle { assertEquals(listOf(prompt), actions.forkReruns) }
    }

    @Test
    fun aRightClickOpensThePromptsActions() = runComposeUiTest {
        val actions = RecordingChatActions()
        val prompt = message("user-2", "user", "Fix the typo")
        setContent { MaterialTheme { RenderRow(single(prompt), rowContext(capabilities = branching), rowCallbacks(actions)) } }

        onNodeWithTag(ChatRowTestTags.USER_PROMPT).performMouseInput { rightClick() }
        onNodeWithText("Edit and resend").performClick()

        runOnIdle { assertEquals(listOf(prompt), actions.edits) }
    }

    @Test
    fun aForkIsNamedInTheDialogWhenTheOwnerCanNameForks() = runComposeUiTest {
        val actions = RecordingChatActions()
        val prompt = message("user-3", "user", "Try another route")
        setContent {
            MaterialTheme {
                RenderRow(single(prompt), rowContext(capabilities = branching.copy(nameForks = true)), rowCallbacks(actions))
            }
        }

        onNodeWithTag(ChatRowTestTags.USER_PROMPT).performTouchInput { longClick() }
        onNodeWithText("Fork from here").performClick()
        onNodeWithTag(ForkNameDialogTags.NAME).performTextInput("Plan B")
        onNodeWithTag(ForkNameDialogTags.CONFIRM).performClick()

        runOnIdle {
            assertEquals(listOf(prompt), actions.forks)
            assertEquals(listOf<String?>("Plan B"), actions.forkNames)
        }
        onNodeWithTag(ForkNameDialogTags.DIALOG).assertDoesNotExist()
    }

    @Test
    fun aForkWithoutNamingGoesStraightThrough() = runComposeUiTest {
        val actions = RecordingChatActions()
        val prompt = message("user-4", "user", "Again")
        setContent { MaterialTheme { RenderRow(single(prompt), rowContext(capabilities = branching), rowCallbacks(actions)) } }

        onNodeWithTag(ChatRowTestTags.USER_PROMPT).performTouchInput { longClick() }
        onNodeWithText("Fork from here").performClick()

        onNodeWithTag(ForkNameDialogTags.DIALOG).assertDoesNotExist()
        runOnIdle { assertEquals(listOf<String?>(null), actions.forkNames) }
    }

    @Test
    fun aReplyCanBeForkedFromItsHoverButton() = runComposeUiTest {
        val actions = RecordingChatActions()
        val reply = message("assistant-1", "assistant", "Here is the plan.")
        setContent { MaterialTheme { RenderRow(single(reply), rowContext(capabilities = branching), rowCallbacks(actions)) } }

        onNodeWithTag(ChatRowTestTags.FORK_REPLY).performMouseInput {
            moveTo(center)
            click(center)
        }
        runOnIdle { assertEquals(listOf(reply), actions.forks) }
    }

    @Test
    fun aReplyWithoutBranchingOffersNoForkButton() = runComposeUiTest {
        val reply = message("assistant-4", "assistant", "Plain.")
        setContent { MaterialTheme { RenderRow(single(reply), rowContext(), rowCallbacks(RecordingChatActions())) } }

        onNodeWithTag(ChatRowTestTags.FORK_REPLY).assertDoesNotExist()
    }

    @Test
    fun aReplyStillStreamingCannotBeForked() = runComposeUiTest {
        val reply = message("assistant-2", "assistant", "Writing")
        setContent {
            MaterialTheme {
                RenderRow(
                    single(reply),
                    rowContext(capabilities = branching).copy(streamingMessageId = "assistant-2"),
                    rowCallbacks(RecordingChatActions()),
                )
            }
        }

        onNodeWithTag(ChatRowTestTags.FORK_REPLY).assertDoesNotExist()
    }

    @Test
    fun aHiddenReplyForkButtonTakesNoClicks() = runComposeUiTest {
        val reply = message("assistant-3", "assistant", "Done.")
        setContent { MaterialTheme { RenderRow(single(reply), rowContext(capabilities = branching), rowCallbacks(RecordingChatActions())) } }

        onNodeWithTag(ChatRowTestTags.FORK_REPLY).assertIsNotEnabled()
    }

    /** Hard cut S9: the legacy bubble's accessibility long-click, on the shared reply. */
    @Test
    fun aReplyOffersItsActionsToAccessibilityAsALongClick() = runComposeUiTest {
        val actions = RecordingChatActions()
        val reply = message("assistant-5", "assistant", "Here is another plan.")
        setContent { MaterialTheme { RenderRow(single(reply), rowContext(capabilities = branching), rowCallbacks(actions)) } }

        onNode(
            SemanticsMatcher("has the Message actions long-click") { node ->
                node.config.getOrNull(SemanticsActions.OnLongClick)?.label == "Message actions"
            },
        ).performSemanticsAction(SemanticsActions.OnLongClick)
        onNodeWithText("Fork from here").performClick()

        runOnIdle { assertEquals(listOf(reply), actions.forks) }
    }
}
