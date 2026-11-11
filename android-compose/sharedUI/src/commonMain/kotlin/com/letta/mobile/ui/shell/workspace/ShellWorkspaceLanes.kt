package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.letta.mobile.data.model.CanvasId
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.ui.host.InspectorFrame
import com.letta.mobile.ui.host.InspectorHost
import com.letta.mobile.ui.shell.modules.ShellModuleSlot
import com.letta.mobile.ui.shell.modules.ShellModuleView
import com.letta.mobile.ui.shell.modules.ShellModules
import com.letta.mobile.ui.theme.LettaDimens

/**
 * Views added for the lanes to draw. What the lanes hold comes from the workspace state's layout;
 * this only supplies views (tests add theirs here), and the shell's own views always win.
 */
internal val LocalShellModules = staticCompositionLocalOf { ShellModules() }

/**
 * The shell's lanes beside the navigation, drawn from the layout in [state]: the main lane inside
 * the inspector frame, then the side lane. With the default config that is the page followed by
 * the host's side pane. A tasks module lists the background tasks of the conversation the
 * navigation has open.
 *
 * A window shows as many main modules side by side as it has room for and a compact one shows one;
 * side modules other than the host's own pane are left out of a compact window.
 */
@Composable
internal fun RowScope.ShellWorkspaceLanes(
    host: LettaShellWorkspaceHost,
    state: LettaShellWorkspaceState,
    inspectors: InspectorHost,
    compact: Boolean,
) {
    val config = state.layout.lanes
    val modules = LocalShellModules.current
    val openConversation by rememberUpdatedState(state.navigation.openConversation)
    val views = remember(host, modules) { host.moduleViews(modules) { openConversation } }
    InspectorFrame(Modifier.weight(1f).fillMaxHeight(), host = inspectors) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val room = if (compact) 1 else (maxWidth / LettaDimens.Pane.sidePanelWidth).toInt()
            MainLane(config.mainLane(room), views)
        }
    }
    config.sideLane().forEach { slot ->
        // Keyed by slot so a module keeps its state when the lane is reordered.
        key(slot) {
            when {
                // The host's pane keeps the size and the dividers it draws for itself.
                slot.view == ShellViews.SIDE_PANE -> ShellModuleSlot(slot, views)
                !compact -> {
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ShellModuleSlot(slot, views, Modifier.width(LettaDimens.Pane.sidePanelWidth).fillMaxHeight())
                }
            }
        }
    }
}

/** The main lane's modules sharing its width; one module takes all of it. */
@Composable
private fun MainLane(slots: List<ModuleSlot>, views: Map<RuntimeViewId, ShellModuleView>) {
    Row(Modifier.fillMaxSize()) {
        slots.forEachIndexed { index, slot ->
            if (index > 0) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            key(slot) { ShellModuleSlot(slot, views, Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

/**
 * The added views with the shell's own bound over them: the page, the side pane, a canvas board and
 * the tasks of the [openConversation] are never replaced.
 */
private fun LettaShellWorkspaceHost.moduleViews(
    modules: ShellModules,
    openConversation: () -> SubagentParentScope?,
): Map<RuntimeViewId, ShellModuleView> = modules.views + mapOf(
    ShellViews.PAGE to { _, modifier -> slots.main(modifier) },
    ShellViews.SIDE_PANE to { _, _ -> slots.sidePane() },
    ShellViews.CANVAS to { slot, modifier -> ShellCanvasModule(slot.objectRef?.takeIf { it.isNotBlank() }?.let(::CanvasId), modifier) },
    ShellViews.NOTE to { slot, modifier -> ShellNoteModule(slot.objectRef?.takeIf { it.isNotBlank() }?.let(::CanvasId), modifier) },
    ShellViews.TASKS to { _, modifier -> ShellTasksModule(openConversation(), modifier) },
)
