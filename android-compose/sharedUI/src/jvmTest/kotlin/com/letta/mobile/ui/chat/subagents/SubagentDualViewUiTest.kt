@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.subagents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.subagents.SubagentView
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.SubagentViewSession
import com.letta.mobile.ui.chat.session.SubagentViewSessions
import com.letta.mobile.ui.chat.surface.ChatSurfaceUiTest
import com.letta.mobile.ui.chat.surface.composer.ComposerTestTags
import com.letta.mobile.ui.chat.surface.timeline.rows.awaitText
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-zxk2n.8 / ulcg6.1: a subagent's conversation opens beside its parent on a wide page
 * and in its place on a compact one, read-only, and the reader can always get back to the parent.
 */
class SubagentDualViewUiTest {
    private val child = SubagentView(AgentId("agent-child-1234"), ConversationId("conv-default-agent-child-1234"), "Audit the build")

    private val childState = ChatUiState(
        conversationState = ConversationState.Ready("conv-default-agent-child-1234"),
        isLoadingMessages = false,
        messages = persistentListOf(
            UiMessage(id = "a1", role = "assistant", content = "Reading the build files", timestamp = "2026-10-16T12:00:00Z"),
        ),
    )

    private val opened = mutableListOf<SubagentView>()
    private val closed = mutableListOf<SubagentView>()
    private val sessions = SubagentViewSessions { view, _ ->
        opened += view
        SubagentViewSession(ChatSurfaceUiTest.TestPort(childState)) { closed += view }
    }

    private fun ComposeUiTest.show(
        holder: SubagentDualViewHolder,
        width: Dp,
        sessions: SubagentViewSessions = this@SubagentDualViewUiTest.sessions,
    ) {
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = width, height = 720.dp)) {
                    SubagentDualViewFrame(SubagentDualViewBinding(holder, sessions)) { modifier ->
                        Box(modifier) { Text("PARENT") }
                    }
                }
            }
        }
    }

    @Test
    fun wideShowsTheSubagentBesideTheParentReadOnly() = runComposeUiTest {
        val holder = SubagentDualViewHolder()
        show(holder, width = 1_200.dp)
        awaitText("PARENT")
        onNodeWithTag(SubagentDualViewTags.PANE).assertDoesNotExist()

        runOnIdle { holder.open(child) }
        awaitText("Reading the build files")
        awaitText("Viewing a subagent. Reply in the parent conversation.")
        onNodeWithTag(SubagentDualViewTags.PARENT).assertExists()
        onNodeWithTag(ComposerTestTags.INPUT, useUnmergedTree = true).assertIsNotEnabled()
        assertEquals(listOf(child), opened)
    }

    @Test
    fun compactSwitchesBetweenTheSubagentAndTheParent() = runComposeUiTest {
        val holder = SubagentDualViewHolder()
        show(holder, width = 400.dp)
        runOnIdle { holder.open(child) }
        awaitText("Reading the build files")
        onNodeWithTag(SubagentDualViewTags.PARENT).assertDoesNotExist()

        onNodeWithTag(SubagentDualViewTags.BACK_TO_PARENT).performClick()
        awaitText("PARENT")
        onNodeWithTag(SubagentDualViewTags.PANE).assertDoesNotExist()
        // The tab stays: the subagent is one tap away.
        awaitText("Audit the build")
    }

    @Test
    fun closingTheViewReturnsToTheParentAndStopsTheChildSession() = runComposeUiTest {
        val holder = SubagentDualViewHolder()
        show(holder, width = 1_200.dp)
        runOnIdle { holder.open(child) }
        awaitText("Reading the build files")

        onNodeWithTag(SubagentDualViewTags.CLOSE_ALL).performClick()
        onNodeWithTag(SubagentDualViewTags.STRIP).assertDoesNotExist()
        runOnIdle {
            assertTrue(!holder.state.isOpen)
            assertEquals(listOf(child), closed)
        }
    }

    @Test
    fun aChildTheHostCannotReachSaysSo() = runComposeUiTest {
        val holder = SubagentDualViewHolder()
        show(holder, width = 1_200.dp, sessions = SubagentViewSessions { _, _ -> null })
        runOnIdle { holder.open(child) }
        onNodeWithTag(SubagentDualViewTags.UNAVAILABLE, useUnmergedTree = true).assertExists()
    }
}
