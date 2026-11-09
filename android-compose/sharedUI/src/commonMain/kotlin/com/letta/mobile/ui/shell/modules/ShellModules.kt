package com.letta.mobile.ui.shell.modules

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.model.view.RuntimeViewId
import com.letta.mobile.data.shell.layout.ModuleSlot
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import com.letta.mobile.ui.theme.LettaDimens

/** Draws one module at whatever size its place gives it; a view never knows where it sits. */
typealias ShellModuleView = @Composable (slot: ModuleSlot, modifier: Modifier) -> Unit

object ShellModuleTags {
    /** A slot whose view no host supplies. */
    const val UNAVAILABLE = "shell-module-unavailable"
}

/**
 * What the shell's lanes hold ([config]) and the views a host adds to draw them ([views]). The
 * shell's own views (the page, the side pane) are bound by the workspace and cannot be replaced here.
 */
@Immutable
class ShellModules(
    val config: ShellLayoutConfig = ShellLayoutConfig.Default,
    val views: Map<RuntimeViewId, ShellModuleView> = emptyMap(),
)

/** One slot drawn by its view, or as unavailable when nothing supplies that view. */
@Composable
fun ShellModuleSlot(slot: ModuleSlot, views: Map<RuntimeViewId, ShellModuleView>, modifier: Modifier = Modifier) {
    key(slot) {
        val view = views[slot.view]
        if (view != null) view(slot, modifier) else UnavailableModule(modifier)
    }
}

@Composable
private fun UnavailableModule(modifier: Modifier) {
    Box(modifier.testTag(ShellModuleTags.UNAVAILABLE), contentAlignment = Alignment.Center) {
        Text(
            "This view isn't available here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(LettaDimens.Space.lg),
        )
    }
}
