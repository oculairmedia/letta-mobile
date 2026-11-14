@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.sidebar

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasDocument
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.ui.canvas.mount.ProvideConversationCanvasPicker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/** How every host's panel lists canvases, and that its canvas controls fit at each viewport. */
class ShellCanvasRowsTest {
    private val now = Instant.fromEpochMilliseconds(10_000_000L)

    private fun canvas(id: String, agent: String?, updated: Long) =
        CanvasDocument(id = CanvasId(id), title = id, agentId = agent, updatedAtEpochMs = updated)

    private val canvases = listOf(
        canvas("other-new", agent = "agent-2", updated = 9_000L),
        canvas("mine-old", agent = "agent-1", updated = 1_000L),
        canvas("mine-new", agent = "agent-1", updated = 5_000L),
        canvas("unowned", agent = null, updated = 7_000L),
        canvas("archived-mine", agent = "agent-1", updated = 8_000L),
    )
    private val archived = setOf(CanvasId("archived-mine"))

    private class Case(val name: String, val scope: ShellCanvasScope, val expected: List<String>)

    @Test
    fun theFocusedAgentsCanvasesLeadThenTheRestByRecencyUnderTheFilter() {
        val agent = AgentId("agent-1")
        val cases = listOf(
            Case(
                "active", ShellCanvasScope(agent, ShellArchiveFilter.Active, archived),
                listOf("mine-new", "mine-old", "other-new", "unowned"),
            ),
            Case("archived", ShellCanvasScope(agent, ShellArchiveFilter.Archived, archived), listOf("archived-mine")),
            Case(
                "all", ShellCanvasScope(agent, ShellArchiveFilter.All, archived),
                listOf("archived-mine", "mine-new", "mine-old", "other-new", "unowned"),
            ),
            Case(
                "no agent", ShellCanvasScope(null, ShellArchiveFilter.Active, archived),
                listOf("other-new", "unowned", "mine-new", "mine-old"),
            ),
        )
        for (case in cases) {
            val rows = ShellSidebarMapping.canvasRows(canvases, case.scope, now)
            assertEquals(case.expected, rows.map { it.id.value }, case.name)
        }
    }

    @Test
    fun theOpenAndArchivedMarksCarryOver() {
        val rows = ShellSidebarMapping.canvasRows(
            canvases,
            ShellCanvasScope(AgentId("agent-1"), ShellArchiveFilter.All, archived, activeCanvasId = CanvasId("unowned")),
            now,
        ).associateBy { it.id.value }
        assertTrue(rows.getValue("unowned").selected)
        assertTrue(rows.getValue("archived-mine").archived)
    }

    @Test
    fun theCanvasControlsFitAtEveryViewport() = runComposeUiTest {
        val state = ShellAgentPanelState(
            agent = ShellPanelAgent(name = "Meridian", agentId = "agent-1"),
            conversations = List(30) { ShellConversationRowModel(id = "c$it", title = "Chat $it", preview = "", timeLabel = "1m") },
            canvases = listOf(
                ShellCanvasRowModel(id = CanvasId("canvas-a"), title = "A board with a long enough title to wrap", timeLabel = "2m"),
            ),
        )
        var width by mutableStateOf(VIEWPORTS.first())
        setContent {
            MaterialTheme {
                ProvideConversationCanvasPicker(null) {
                    ShellAgentPanel(state, ShellAgentPanelActions(onNewCanvas = {}), Modifier.width(width.dp).height(720.dp))
                }
            }
        }
        for (viewport in VIEWPORTS) {
            width = viewport
            waitForIdle()
            val jump = onNodeWithTag(ShellCanvasTags.JUMP).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(jump.right <= viewport.dp, "jump fits at $viewport")
            onNodeWithTag(ShellCanvasTags.JUMP).performClick()
            waitForIdle()
            val add = onNodeWithTag(ShellCanvasTags.NEW).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(add.right <= viewport.dp, "new canvas fits at $viewport")
        }
    }

    private companion object {
        val VIEWPORTS = listOf(360, 700, 760, 1200)
    }
}
