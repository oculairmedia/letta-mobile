@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.shell.acceptance

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.ui.canvas.LocalCanvasSessionRegistry
import com.letta.mobile.ui.canvas.boards.ProvideCanvasBoardSource
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspace
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceHost
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceSlots
import com.letta.mobile.ui.shell.workspace.LettaShellWorkspaceState
import com.letta.mobile.ui.shell.workspace.ShellWindowChrome
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceDrawer
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceEvents
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceHeaderState
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceLayout
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigation
import com.letta.mobile.ui.shell.workspace.ShellWorkspaceNavigationActions
import com.letta.mobile.ui.shell.workspace.shellCanvasModuleTag
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class ObjectIdentityAcrossViewsTest {
    @Test
    fun duplicateSlotAcrossLanesIsDrawnOnceAndRemainsWhenMainIsRemoved() = runDesktopComposeUiTest(width = 1800, height = 800) {
        val id = CanvasId("shared-board")
        val store = InMemoryCanvasDocumentStore()
        runBlocking { CanvasSession.create(store, CanvasCreateOptions(title = "Original", canvasId = id)) }
        val registry = CanvasSessionRegistry()
        val boards = CanvasBoardSessions(store, live = registry)
        val slot = ModuleSlot(ShellViews.CANVAS, id.value)
        val page = ModuleSlot(ShellViews.PAGE)
        var main by mutableStateOf(listOf(page, slot))
        var side by mutableStateOf(emptyList<ModuleSlot>())
        val host = LettaShellWorkspaceHost(
            actions = ShellWorkspaceNavigationActions(),
            slots = LettaShellWorkspaceSlots(main = { Box(it) }, agentCard = {}, sidePane = {}, overlays = {}),
            chrome = ShellWindowChrome.Hoisted {}, drawer = ShellWorkspaceDrawer(), events = ShellWorkspaceEvents(),
        )
        setContent {
            val config = ShellLayoutConfig(ShellLayoutConfig.VERSION, mapOf(ShellLanes.MAIN to main, ShellLanes.SIDE to side))
            val state = LettaShellWorkspaceState(
                header = ShellWorkspaceHeaderState(title = "Window"),
                navigation = ShellWorkspaceNavigation(
                    rail = ShellLibraryRailState(agents = listOf("agent-a" to "Ada"), focus = ShellRailFocus(selectedAgentId = "agent-a")),
                    panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Ada", agentId = "agent-a")),
                ),
                layout = ShellWorkspaceLayout(lanes = config),
            )
            CompositionLocalProvider(LocalCanvasSessionRegistry provides registry) {
                ProvideCanvasBoardSource(boards) { LettaShellWorkspace(state, host, Modifier.size(1800.dp, 800.dp)) }
            }
        }
        waitUntil(timeoutMillis = 10_000) { registry.get(id) != null }
        val first = registry.get(id)!!
        assertEquals(1, boards.holds(id))
        runOnIdle { side = listOf(slot) }
        waitForIdle()
        assertEquals(1, onAllNodesWithTag(shellCanvasModuleTag(id)).fetchSemanticsNodes().size)
        assertSame(first, registry.get(id))
        assertEquals(1, boards.holds(id))
        runOnIdle { main = listOf(page) }
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithTag(shellCanvasModuleTag(id)).fetchSemanticsNodes().size == 1 && registry.get(id) != null
        }
        assertSame(first, registry.get(id))
        val acquired = runBlocking { boards.acquire(id) }!!
        assertSame(first, acquired)
        boards.release(acquired)
        runBlocking { acquired.updateTitle("Edited") }
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasText("Edited", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        runOnIdle { side = emptyList() }
        waitUntil(timeoutMillis = 10_000) { registry.get(id) == null }
        assertEquals(0, boards.holds(id))
    }
}
