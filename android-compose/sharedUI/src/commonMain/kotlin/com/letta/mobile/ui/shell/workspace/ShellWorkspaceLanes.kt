package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import com.letta.mobile.ui.chat.surface.LocalChatLane
import com.letta.mobile.ui.chat.surface.LocalChatModuleContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.data.shell.layout.ShellViews
import com.letta.mobile.data.shell.layout.edit.SharedNotesPlacement
import com.letta.mobile.ui.shell.belowFloatingChrome
import com.letta.mobile.ui.host.InspectorFrame
import com.letta.mobile.ui.secrets.SecretRequestModule
import com.letta.mobile.ui.secrets.SecretRequestMount
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
 * navigation has open. While a secret request is pending, the shell's own secret request card sits
 * above the main lane, outside every layout ([ShellViews.HostOwned]).
 *
 * A window shows as many main modules side by side as it has room for and a compact one shows one;
 * side modules other than the host's own pane are left out of a compact window, except the
 * shared-notes view, which a compact window draws over the page from either lane (the page stays
 * mounted beneath, so the main agent's chat keeps its state) and which closes the navigation drawer
 * as it appears.
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
    val chat = remember(config) { config.chatLane() }
    val chatLane = remember(chat) { chat.map { it.view to it.objectRef } }
    val chatContent: @Composable (Int, Modifier) -> Unit = remember(chat, views) {
        { index, modifier -> ShellModuleSlot(chat[index], views, modifier) }
    }
    InspectorFrame(Modifier.weight(1f).fillMaxHeight(), host = inspectors) {
        Column(Modifier.fillMaxSize()) {
            // Reserved for the shell: no layout holds this view, so no edit can move, remove or cover it.
            SecretRequestMount { modifier -> ShellModuleSlot(SecretRequestSlot, views, modifier) }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val room = if (compact) 1 else (maxWidth / LettaDimens.Pane.sidePanelWidth).toInt()
                CompositionLocalProvider(
                    LocalChatLane provides chatLane,
                    LocalChatModuleContent provides chatContent,
                ) {
                    MainLane(config.mainLane(room), views)
                }
                if (compact) CompactDocumentLayer(host, config, views)
            }
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

/** The pending secret request card's slot: mounted by the shell above the main lane, never by a layout. */
private val SecretRequestSlot = ModuleSlot(ShellViews.SECRET_REQUEST)

/**
 * A compact window's shared-notes view: over the page, below the floating header, while either lane
 * holds it (the compact main lane draws only the page). Showing it closes the drawer the person
 * toggled it from, so it is seen at once.
 */
@Composable
private fun CompactDocumentLayer(
    host: LettaShellWorkspaceHost,
    config: ShellLayoutConfig,
    views: Map<RuntimeViewId, ShellModuleView>,
) {
    val shown = SharedNotesPlacement.overCompactPage(config)
    LaunchedEffect(shown) { if (shown) host.drawer.open = false }
    if (!shown) return
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        ShellModuleSlot(SharedNotesPlacement.SLOT, views, Modifier.fillMaxSize().belowFloatingChrome())
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
 * The added views with the shell's own bound over them: the page, the side pane, a canvas board,
 * the tasks of the [openConversation], the shared notes and the pending secret request card are
 * never replaced.
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
    ShellViews.DOCUMENT to { _, modifier -> ShellDocumentModule(modifier) },
    ShellViews.SECRET_REQUEST to { _, modifier -> SecretRequestModule(modifier) },
)
