@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.composer.AgentMentions
import com.letta.mobile.data.composer.MentionableAgent
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatModelUiState
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatWorkingDirectoryUiState
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-zxk2n.6: `@agent` mentions and the worktree badge in the shared composer. */
class ComposerAgentMentionUiTest {
    private val bot = MentionableAgent(id = "agent-123", name = "Research Bot")

    private fun composer(text: String) = ChatComposerUiState(
        text = text,
        canSend = true,
        model = ChatModelUiState(currentHandle = null, currentLabel = "Model"),
        mentionables = persistentListOf(AgentMentions.mentionable(bot)),
    )

    @Test
    fun pickingAnAgentInsertsItsReferenceAndShowsAChip() = runComposeUiTest {
        var text by mutableStateOf("ask @res")
        val actions = RecordingChatActions(onText = { text = it })
        setContent { MaterialTheme { ChatComposerPanel(ComposerInputs(composer = composer(text), actions = actions)) } }

        onNodeWithText("Research Bot").performClick()

        runOnIdle { assertEquals("ask @Research Bot (agent agent-123) ", text) }
        onNodeWithTag(ComposerAgentMentionTags.CHIP + "agent-123").assertExists()
    }

    @Test
    fun removingTheChipRemovesTheWholeReference() = runComposeUiTest {
        var text by mutableStateOf("ask @Research Bot (agent agent-123) about it")
        val actions = RecordingChatActions(onText = { text = it })
        setContent { MaterialTheme { ChatComposerPanel(ComposerInputs(composer = composer(text), actions = actions)) } }

        onNodeWithTag(ComposerAgentMentionTags.CHIP + "agent-123").performClick()

        runOnIdle { assertEquals("ask about it", text) }
        onNodeWithTag(ComposerAgentMentionTags.ROW).assertDoesNotExist()
    }

    @Test
    fun aDraftWithoutReferencesHasNoChipRow() = runComposeUiTest {
        setContent { MaterialTheme { ChatComposerPanel(ComposerInputs(composer = composer("plain text"), actions = RecordingChatActions())) } }
        onNodeWithTag(ComposerAgentMentionTags.ROW).assertDoesNotExist()
    }

    @Test
    fun aWorkingDirectoryInsideAWorktreeShowsTheBadgeAndOpensThePicker() = runComposeUiTest {
        var picks = 0
        val state = composer("").copy(workingDirectory = ChatWorkingDirectoryUiState(path = "/work/repo/.letta/worktrees/fix-login"))
        setContent {
            MaterialTheme {
                ChatComposerPanel(
                    ComposerInputs(
                        composer = state,
                        actions = RecordingChatActions(),
                        capabilities = ChatSurfaceCapabilities(workingDirectory = true),
                        host = ChatSurfaceHost(pickWorkingDirectory = { picks++ }),
                    ),
                )
            }
        }
        onNodeWithTag(ComposerWorktreeTags.BADGE, useUnmergedTree = true).assertExists()
        onNodeWithText("worktree fix-login", useUnmergedTree = true).assertExists()
        onNodeWithTag(ComposerTestTags.WORKING_DIRECTORY).performClick()
        runOnIdle { assertEquals(1, picks) }
    }
}
