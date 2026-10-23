@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.chat.tools.bundled.BundledToolPresenters
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityControllerState
import com.letta.mobile.data.memory.MemoryParityGraph
import com.letta.mobile.data.memory.MemoryParityItem
import com.letta.mobile.data.memory.MemoryParitySection
import com.letta.mobile.data.memory.MemoryParitySectionKind
import com.letta.mobile.data.memory.MemoryParityState
import com.letta.mobile.data.memory.graph.MemoryPageActions
import com.letta.mobile.data.memory.graph.MemoryPageState
import com.letta.mobile.data.memory.memfs.MemfsPageActions
import com.letta.mobile.data.memory.memfs.MemfsPageState
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.shell.pages.ShellMemoryPreview
import com.letta.mobile.ui.shell.pages.ShellMemoryPreviewTags
import com.letta.mobile.ui.shell.pages.ShellMemorySources
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-2ccpv: a memory step's chip carries the block's usage and opens the shell's own
 * Memory view in the detail pane, focused on the edited block (or MemFS entry), with the edit and
 * the conversation's earlier edits of the same entry.
 */
class MemoryToolPreviewUiTest {
    private val calls = mutableListOf<String>()

    private val memory = MemoryPageState(
        parity = MemoryParityControllerState(
            memory = MemoryParityState(
                selectedAgentId = "agent-1",
                sections = listOf(
                    MemoryParitySection(
                        kind = MemoryParitySectionKind.Memory,
                        title = "Memory",
                        subtitle = "",
                        emptyMessage = "",
                        items = listOf(
                            MemoryParityItem.MemoryBlock(
                                id = "block-1", title = "persona", subtitle = "", detailText = "", metadataLabels = emptyList(),
                                links = emptyList(), preview = "", limit = 5000, readOnly = false, label = "persona", length = 812,
                            ),
                        ),
                    ),
                ),
                graph = MemoryParityGraph(
                    nodes = listOf(MemoryGraphNode("node-persona", "persona", "", MemoryGraphNodeKind.Memory, "block-1")),
                ),
            ),
        ),
    )

    private fun replace(id: String, old: String, new: String) = UiToolCall(
        name = "memory_replace",
        arguments = """{"label":"persona","old_str":"$old","new_str":"$new"}""",
        result = "OK",
        status = "success",
        toolCallId = id,
    )

    private val earlier = replace("call-1", "likes tea", "likes coffee")
    private val unrelated =
        UiToolCall(name = "Bash", arguments = """{"command":"ls"}""", result = "a", status = "success", toolCallId = "call-2")
    private val latest = replace("call-3", "is calm", "is cheerful")

    private fun ComposeUiTest.show(conversation: List<UiToolCall>, opened: UiToolCall) {
        val sources = ShellMemorySources(
            memory = MutableStateFlow(memory),
            memoryActions = recording<MemoryPageActions>(),
            files = MutableStateFlow(MemfsPageState(agentId = "agent-1")),
            filesActions = recording<MemfsPageActions>(),
            selectFilesAgent = { calls += "selectFilesAgent:${it?.value}" },
        )
        val preview = ShellMemoryPreview(sources) { AgentId("agent-1") }
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalMemoryPreview provides preview) {
                    Column {
                        ToolPreviewChips(opened, BundledToolPresenters.registry.line(opened.facts()), rowCallbacks())
                        ToolPreviewPanes { conversation }
                    }
                }
            }
        }
    }

    @Test
    fun aBlockChipShowsItsUsageAndOpensTheMemoryViewFocusedOnTheBlockWithItsEarlierEdits() = runComposeUiTest {
        show(listOf(earlier, unrelated, latest), opened = latest)

        onNodeWithTag(ShellMemoryPreviewTags.PANE).assertDoesNotExist()
        onNodeWithText("persona · 812/5000").performClick()

        onNodeWithTag(ShellMemoryPreviewTags.PANE).assertExists()
        runOnIdle {
            assertTrue("refresh" in calls, "calls: $calls")
            assertTrue("selectNode" in calls, "calls: $calls")
        }
        onNodeWithText("This edit").assertExists()
        onNodeWithText("812 of 5000 characters").assertExists()
        // This edit's changed words, then the earlier edit of the same block; the shell step is not one.
        onNodeWithText("+ cheerful", substring = true, useUnmergedTree = true).assertExists()
        onNodeWithTag(ShellMemoryPreviewTags.EARLIER).assertExists()
        onNodeWithText("+ coffee", substring = true, useUnmergedTree = true).assertExists()
        onAllNodesWithText("Edited memory persona", useUnmergedTree = true).assertCountEquals(2)
    }

    @Test
    fun aMemfsChipOpensTheEntryInTheMemoryFiles() = runComposeUiTest {
        val write = UiToolCall(
            name = "memory",
            arguments = """{"command":"create","file_path":"system/human/prefs.md","file_text":"hi"}""",
            result = "OK",
            status = "success",
            toolCallId = "call-7",
        )
        show(listOf(write), opened = write)

        onNodeWithText("prefs.md").performClick()

        onNodeWithTag(ShellMemoryPreviewTags.PANE).assertExists()
        runOnIdle {
            assertTrue("selectFilesAgent:agent-1" in calls, "calls: $calls")
            assertEquals(1, calls.count { it == "openFile" }, "calls: $calls")
        }
        onNodeWithTag(ShellMemoryPreviewTags.EARLIER).assertDoesNotExist()
    }

    /** Every interface call is recorded by name; the Object methods behave as identity. */
    private inline fun <reified T : Any> recording(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> T::class.java.simpleName
                else -> {
                    calls += method.name
                    null
                }
            }
        } as T
}
