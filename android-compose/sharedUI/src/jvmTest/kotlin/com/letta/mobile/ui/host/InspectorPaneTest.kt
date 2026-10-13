@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.context.ContextTokenReadings
import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.ui.chat.subagents.BackgroundTasksSidePane
import com.letta.mobile.ui.chat.subagents.BackgroundTasksTags
import com.letta.mobile.ui.context.AgentContextCardActions
import com.letta.mobile.ui.context.AgentContextCardBinding
import com.letta.mobile.ui.context.AgentContextCardDeps
import com.letta.mobile.ui.context.AgentContextCardHost
import com.letta.mobile.ui.context.AgentContextFocus
import com.letta.mobile.ui.context.AgentContextTags
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-ckmub.3.15 (S13, P6): the inspector docks when its frame is wide and is a sheet when compact. */
class InspectorPaneTest {
    @Test
    fun thePresentationFollowsTheFramesWidth() {
        assertEquals(InspectorPresentation.Docked, inspectorPresentation(LettaDimens.Pane.wideBreakpoint))
        assertEquals(InspectorPresentation.Docked, inspectorPresentation(1_200.dp))
        assertEquals(InspectorPresentation.Sheet, inspectorPresentation(LettaDimens.Pane.wideBreakpoint - 1.dp))
        assertEquals(InspectorPresentation.Sheet, inspectorPresentation(400.dp))
    }

    @Test
    fun aWideFrameDocksTheInspectorOnTheRight() = runComposeUiTest {
        val containers = recordingSheets()
        setContent { Frame(WIDE, containers.host) { Detail(onClose = {}) } }
        onNodeWithTag(InspectorTags.DOCKED).assertExists()
        onNode(hasTestTag(BODY) and hasAnyAncestor(hasTestTag(InspectorTags.DOCKED))).assertExists()
        assertEquals(emptyList(), containers.opened)
        // Docked beside the page, not over it: the page keeps the left of the frame.
        val page = onNodeWithTag(PAGE).fetchSemanticsNode().boundsInRoot
        val pane = onNodeWithTag(InspectorTags.DOCKED).fetchSemanticsNode().boundsInRoot
        assertEquals(true, pane.left >= page.right, "inspector $pane sits right of the page $page")
    }

    @Test
    fun aCompactFrameOpensTheInspectorAsASheet() = runComposeUiTest {
        val containers = recordingSheets()
        setContent { Frame(COMPACT, containers.host) { Detail(onClose = {}) } }
        onNodeWithTag(InspectorTags.DOCKED).assertDoesNotExist()
        onNodeWithTag(BODY).assertExists()
        assertEquals(listOf("sheet"), containers.opened.distinct())
    }

    @Test
    fun withoutAFrameTheInspectorIsTheHostsModal() = runComposeUiTest {
        val containers = recordingSheets()
        // A phone page: the window is compact too (a wide touch window's modal is a dialog: LettaPaneTest).
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalPaneWindowClass provides PaneWindowClass.Compact) {
                    ProvideLettaHost(containers.host) { Detail(onClose = {}) }
                }
            }
        }
        onNodeWithTag(BODY).assertExists()
        assertEquals(listOf("sheet"), containers.opened.distinct())
    }

    @Test
    fun closeAsksTheOwnerAndTheNewestOpenPaneShows() = runComposeUiTest {
        var first by mutableStateOf(true)
        var second by mutableStateOf(true)
        setContent {
            Frame(WIDE, LettaHost.Touch) {
                if (first) InspectorPane(title = "First", onClose = { first = false }) { Text("first body") }
                if (second) InspectorPane(title = "Second", onClose = { second = false }) { Text("second body") }
            }
        }
        onNodeWithText("second body").assertExists()
        onNodeWithText("first body").assertDoesNotExist()
        onNodeWithContentDescription("Close").performClick()
        waitForIdle()
        assertEquals(false, second)
        onNodeWithText("first body").assertExists()
    }

    @Test
    fun aControlOutsideTheFrameOpensItsDetailInTheFrame() = runComposeUiTest {
        val deps = AgentContextCardDeps(ContextTokenReadings(), breakdown = null, compaction = null, pickerSource = null)
        val focus = AgentContextFocus(
            agentId = "agent-1",
            conversationId = null,
            modelLabel = "Claude Opus",
            modelValue = null,
            effort = null,
            windowTokens = 200_000,
            turnRunning = false,
        )
        setContent {
            MaterialTheme {
                val inspectors = rememberInspectorHost()
                ProvideInspectorHost(inspectors) {
                    // The sidebar's context card, beside the frame that keeps the window's details.
                    Row(Modifier.size(WIDE + SIDEBAR, 600.dp)) {
                        Box(Modifier.width(SIDEBAR).fillMaxHeight()) {
                            AgentContextCardHost(AgentContextCardBinding(deps, AgentContextCardActions(onModelSelected = {})), focus)
                        }
                        InspectorFrame(Modifier.width(WIDE).fillMaxHeight(), host = inspectors) { Box(Modifier) }
                    }
                }
            }
        }
        onNodeWithTag(AgentContextTags.CARD).performClick()
        waitForIdle()
        onNode(hasTestTag(AgentContextTags.SHEET) and hasAnyAncestor(hasTestTag(InspectorTags.DOCKED))).assertExists()
    }

    @Test
    fun theBackgroundTasksRosterIsAnInspector() = runComposeUiTest {
        var open by mutableStateOf(true)
        setContent { Frame(WIDE, LettaHost.Pointer) { if (open) BackgroundTasksSidePane(subagents = emptyList<SubagentEntry>(), onClose = { open = false }) } }
        onNode(hasTestTag(BackgroundTasksTags.PANEL) and hasAnyAncestor(hasTestTag(InspectorTags.DOCKED))).assertExists()
        onNodeWithText("Background tasks").assertExists()
        onNodeWithContentDescription("Close").performClick()
        waitForIdle()
        onNodeWithTag(InspectorTags.DOCKED).assertDoesNotExist()
    }

    @Composable
    private fun Frame(width: Dp, host: LettaHost, content: @Composable () -> Unit) {
        MaterialTheme {
            ProvideLettaHost(host) {
                InspectorFrame(Modifier.size(width, 600.dp)) {
                    Column(Modifier.testTag(PAGE)) { Text("page") }
                    content()
                }
            }
        }
    }

    @Composable
    private fun Detail(onClose: () -> Unit) {
        InspectorPane(title = "Details", onClose = onClose, testTag = BODY) { Text("detail body") }
    }

    private class Recording(val host: LettaHost, val opened: MutableList<String>)

    /** Touch containers that record which one drew the pane, then draw it in place. */
    private fun recordingSheets(): Recording {
        val opened = mutableListOf<String>()
        fun recorder(kind: String): PaneContainer = { spec, content ->
            opened += kind
            Column(spec.testTag?.let { Modifier.testTag(it) } ?: Modifier) { content() }
        }
        return Recording(LettaHost.Touch.copy(sheet = recorder("sheet"), dialog = recorder("dialog")), opened)
    }

    private companion object {
        const val BODY = "inspector-body"
        const val PAGE = "inspector-page"
        val WIDE = 1_000.dp
        val COMPACT = 400.dp
        val SIDEBAR = 240.dp
    }
}
