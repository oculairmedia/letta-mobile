package com.letta.mobile.ui.chat.subagents

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.data.subagents.SubagentRoster
import com.letta.mobile.data.subagents.SubagentRosterItem
import com.letta.mobile.ui.components.list.RosterSection

/** One roster row: a running [card] (activity lines) or an ended row. */
@Immutable
internal data class SubagentRosterLine(val item: SubagentRosterItem, val card: Boolean)

/** The section headers' actions: Finished's Clear and Background's Show / Hide. */
internal class SubagentRosterHeaderActions(val onClearFinished: () -> Unit, val onToggleBackground: () -> Unit)

internal fun subagentRosterSections(
    roster: SubagentRoster,
    backgroundShown: Boolean,
    actions: SubagentRosterHeaderActions,
): List<RosterSection<SubagentRosterLine>> = buildList {
    if (roster.running.isNotEmpty()) {
        add(
            RosterSection(
                "running", roster.running.map { SubagentRosterLine(it, card = true) },
                title = "Running · ${roster.running.size}",
            ),
        )
    }
    if (roster.finished.isNotEmpty()) {
        add(
            RosterSection(
                "finished",
                roster.finished.map { SubagentRosterLine(it, card = false) },
                title = "Finished · ${roster.finished.size}",
                headerEnd = { SectionAction(CLEAR_LABEL, actions.onClearFinished) },
            ),
        )
    }
    if (roster.background.isNotEmpty()) {
        val shown = if (backgroundShown) roster.background else emptyList()
        add(
            RosterSection(
                "background",
                shown.map { SubagentRosterLine(it, card = false) },
                title = "Background · ${roster.background.size}",
                headerEnd = { SectionAction(if (backgroundShown) HIDE_LABEL else SHOW_LABEL, actions.onToggleBackground) },
            ),
        )
    }
}

@Composable
private fun SectionAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

private const val CLEAR_LABEL = "Clear"
private const val SHOW_LABEL = "Show"
private const val HIDE_LABEL = "Hide"
