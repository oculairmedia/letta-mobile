package com.letta.mobile.ui.shell.acceptance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.canvas.CanvasCreateOptions
import com.letta.mobile.data.canvas.CanvasSession
import com.letta.mobile.data.canvas.CanvasSessionRegistry
import com.letta.mobile.data.canvas.InMemoryCanvasDocumentStore
import com.letta.mobile.data.canvas.library.CanvasBoardSessions
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLanes
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.ui.settings.LocalShellLayoutReset
import com.letta.mobile.ui.shell.rail.ShellLibraryRailState
import com.letta.mobile.ui.shell.rail.ShellRailFocus
import com.letta.mobile.ui.shell.sidebar.ShellAgentPanelState
import com.letta.mobile.ui.shell.sidebar.ShellPanelAgent
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
import kotlinx.coroutines.runBlocking

internal class AcceptanceWorkspace {
    val id = CanvasId("acceptance-board")
    val store = InMemoryCanvasDocumentStore()
    val registry = CanvasSessionRegistry()
    val boards = CanvasBoardSessions(store, live = registry)
    val page = ModuleSlot(ShellViews.PAGE)
    val canvas = ModuleSlot(ShellViews.CANVAS, id.value)
    val tasks = ModuleSlot(ShellViews.TASKS)

    init {
        runBlocking {
            CanvasSession.create(store, CanvasCreateOptions(canvasId = id, title = "Acceptance board",
                initialSceneJson = """{"elements":[],"_future":{"content":"keep me"}}"""))
        }
    }

    fun lanes(main: List<ModuleSlot> = listOf(page), side: List<ModuleSlot> = emptyList()) =
        ShellLayoutConfig(ShellLayoutConfig.VERSION, mapOf(ShellLanes.MAIN to main, ShellLanes.SIDE to side))

    fun state(config: ShellLayoutConfig) = LettaShellWorkspaceState(
        header = ShellWorkspaceHeaderState(title = "Acceptance"),
        navigation = ShellWorkspaceNavigation(
            rail = ShellLibraryRailState(agents = listOf("agent-a" to "Ada"), focus = ShellRailFocus(selectedAgentId = "agent-a")),
            panel = ShellAgentPanelState(agent = ShellPanelAgent(name = "Ada", agentId = "agent-a")),
            openConversation = SubagentParentScope("agent-a", "conv-a"),
        ),
        layout = ShellWorkspaceLayout(lanes = config),
    )

    fun host(actions: ShellWorkspaceNavigationActions = ShellWorkspaceNavigationActions()) = LettaShellWorkspaceHost(
        actions = actions,
        slots = LettaShellWorkspaceSlots(main = { modifier ->
            Box(modifier.testTag(PAGE)) {
                LocalShellLayoutReset.current?.let { reset -> Text("Reset layout", Modifier.testTag(RESET).clickable(onClick = reset)) }
            }
        }, agentCard = {}, sidePane = {}, overlays = {}),
        chrome = ShellWindowChrome.Hoisted {}, drawer = ShellWorkspaceDrawer(), events = ShellWorkspaceEvents(),
    )

    companion object {
        const val PAGE = "acceptance-page"
        const val RESET = "acceptance-reset"
        const val TIMEOUT = 10_000L
    }
}
