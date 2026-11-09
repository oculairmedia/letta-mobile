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
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.ui.host.InspectorFrame
import com.letta.mobile.ui.host.InspectorHost
import com.letta.mobile.ui.shell.modules.ShellModuleSlot
import com.letta.mobile.ui.shell.modules.ShellModuleView
import com.letta.mobile.ui.shell.modules.ShellModules
import com.letta.mobile.ui.theme.LettaDimens

/**
 * What the lanes hold. Nothing saves or loads a layout yet, so the workspace always draws the
 * default; hosts get a way to supply this once there is a stored layout for them to supply.
 */
internal val LocalShellModules = staticCompositionLocalOf { ShellModules() }

/**
 * The shell's lanes beside the navigation, drawn from the shell's layout config
 * (letta-mobile-n2stx.2): the main lane inside the inspector frame, then the side lane. With the
 * default config that is the page followed by the host's side pane.
 *
 * A window shows as many main modules side by side as it has room for and a compact one shows one;
 * side modules other than the host's own pane are left out of a compact window.
 */
@Composable
internal fun RowScope.ShellWorkspaceLanes(host: LettaShellWorkspaceHost, inspectors: InspectorHost, compact: Boolean) {
    val modules = LocalShellModules.current
    val views = remember(host, modules) { host.moduleViews(modules) }
    val config = modules.config
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

/** The added views with the shell's own bound over them: the page and the side pane are never replaced. */
private fun LettaShellWorkspaceHost.moduleViews(modules: ShellModules): Map<RuntimeViewId, ShellModuleView> = modules.views + mapOf(
    ShellViews.PAGE to { _, modifier -> slots.main(modifier) },
    ShellViews.SIDE_PANE to { _, _ -> slots.sidePane() },
)
