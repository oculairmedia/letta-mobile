@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.subagents

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.model.SubagentActivitySnapshot
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.subagents.QUIET_AFTER_MS
import com.letta.mobile.data.subagents.SubagentRosterItem
import com.letta.mobile.data.subagents.projectSubagentRoster
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-ulcg6 (PR4): the shared subagent roster both clients render.
 * A quiet subagent reads as running-but-quiet (never failed), an external
 * coding agent carries its badge, a finished one offers its conversation, and
 * reflection runs stay behind the collapsed Background section.
 */
class SubagentRosterListUiTest {
    private val now = 1_791_642_000_000L

    private fun entry(id: String, status: String = "running", type: String = "General-purpose") = SubagentEntry(
        toolCallId = id,
        description = "work $id",
        subagentType = type,
        status = status,
        parentAgentId = "agent-parent",
        parentConversationId = "local-conv-517",
        startedAt = (now - 88_000L).toString(),
    )

    private val entries = listOf(
        entry("toolu_quiet").copy(
            toolCallCount = 12,
            lastActivityAtMs = now - QUIET_AFTER_MS - 1,
            activity = SubagentActivitySnapshot(lines = listOf("Read src/A.kt", "Bash git status")),
        ),
        entry("toolu_cc", type = "Claude-code"),
        entry("toolu_done", status = "success").copy(subagentAgentId = "agent-local-b", subagentConversationId = "default"),
        entry("subagent-1791641853470-1", type = "Reflection"),
    )

    @Test
    fun rendersTheSharedRosterHonestly() = runComposeUiTest {
        var opened: SubagentRosterItem? = null
        setContent {
            MaterialTheme {
                SubagentRosterList(
                    roster = projectSubagentRoster(entries, now),
                    onOpenConversation = { opened = it },
                    onClearFinished = {},
                )
            }
        }

        onNodeWithTag(SubagentRosterTags.running("toolu_quiet")).assertIsDisplayed()
        onNodeWithTag(SubagentRosterTags.subtitle("toolu_quiet"), useUnmergedTree = true)
            .assertTextContains("12 tools", substring = true)
            .assertTextContains("quiet", substring = true)
        // Only the latest activity line until the card is tapped.
        onNodeWithText("› Bash git status").assertIsDisplayed()
        onNodeWithText("› Read src/A.kt").assertDoesNotExist()
        onNodeWithTag(SubagentRosterTags.running("toolu_quiet")).performClick()
        onNodeWithText("› Read src/A.kt").assertIsDisplayed()

        onNodeWithTag(SubagentRosterTags.badge("toolu_cc"), useUnmergedTree = true).assertTextContains("Claude Code")
        onNodeWithTag(SubagentRosterTags.open("toolu_quiet")).assertDoesNotExist()

        onNodeWithTag(SubagentRosterTags.open("toolu_done")).performClick()
        assertEquals("agent-local-b", opened?.childAgentId)

        onNodeWithTag(SubagentRosterTags.ended("subagent-1791641853470-1")).assertDoesNotExist()
        onNodeWithText("Show").performClick()
        onNodeWithTag(SubagentRosterTags.ended("subagent-1791641853470-1")).assertIsDisplayed()
    }

    @Test
    fun hostWithoutNavigationOffersNoConversationAction() = runComposeUiTest {
        setContent {
            MaterialTheme {
                SubagentRosterList(roster = projectSubagentRoster(entries, now), onOpenConversation = null, onClearFinished = {})
            }
        }
        onNodeWithTag(SubagentRosterTags.open("toolu_done")).assertDoesNotExist()
    }

    @Test
    fun clearHidesFinishedRows() = runComposeUiTest {
        setContent {
            MaterialTheme {
                var cleared by remember { mutableStateOf(emptySet<String>()) }
                val roster = projectSubagentRoster(entries, now, cleared)
                SubagentRosterList(
                    roster = roster,
                    onOpenConversation = null,
                    onClearFinished = { cleared = cleared + roster.finished.map { it.key } },
                )
            }
        }
        onNodeWithTag(SubagentRosterTags.ended("toolu_done")).assertIsDisplayed()
        onNodeWithText("Clear").performClick()
        onNodeWithTag(SubagentRosterTags.ended("toolu_done")).assertDoesNotExist()
    }
}
