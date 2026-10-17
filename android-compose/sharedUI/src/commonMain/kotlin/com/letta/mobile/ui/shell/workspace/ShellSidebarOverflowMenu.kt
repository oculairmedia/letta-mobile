package com.letta.mobile.ui.shell.workspace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.shell_new_chat
import com.letta.mobile.sharedui.resources.shell_settings
import com.letta.mobile.sharedui.resources.shell_sidebar_more
import com.letta.mobile.sharedui.resources.shell_sidebar_more_description
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaPopupMenu
import com.letta.mobile.ui.host.LocalLettaHost
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.sidebar.shellSectionIcon
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/** The agent sections the collapsed sidebar's overflow lists, in the sidebar's order. */
private val OverflowSections = listOf(
    LensDestination.Memory,
    LensDestination.Schedules,
    LensDestination.Channels,
    LensDestination.Skills,
)

/**
 * The sections that live inside the collapsible sidebar (New chat, Memory, Schedules, Channels,
 * Skills, Settings), reachable without reopening it (o5m90 AC #6). Conversation history is the
 * command palette's (Ctrl/Cmd+K).
 */
@Composable
fun ShellSidebarOverflowMenu(overflow: ShellSidebarOverflow, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val mode = overflow.mode
    val label = stringResource(
        Res.string.shell_sidebar_more_description,
        WorkPlayLens.destinationLabel(mode, LensDestination.Memory),
        WorkPlayLens.destinationLabel(mode, LensDestination.Schedules),
    )
    val newChat = stringResource(Res.string.shell_new_chat)
    val settings = stringResource(Res.string.shell_settings)
    Box(modifier) {
        LocalLettaHost.current.tooltip(stringResource(Res.string.shell_sidebar_more)) {
            IconButton(onClick = { expanded = true }, modifier = Modifier.semantics { contentDescription = label }) {
                Icon(
                    imageVector = LettaIcons.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(LettaDimens.Control.icon),
                )
            }
        }
        LettaPopupMenu(
            expanded = expanded,
            onDismiss = { expanded = false },
            items = buildList {
                add(LettaMenuItem(label = newChat, icon = LettaIcons.Add, onClick = overflow.onNewChat))
                OverflowSections.forEach { section ->
                    val sectionLabel = WorkPlayLens.destinationLabel(mode, section)
                    add(LettaMenuItem(label = sectionLabel, icon = shellSectionIcon(mode, section)) { overflow.onDestination(section) })
                }
                // Settings is not a lens section (the lens only renames the agent's sections).
                add(LettaMenuItem(label = settings, icon = LettaIcons.Settings, onClick = overflow.onSettings))
            },
        )
    }
}
