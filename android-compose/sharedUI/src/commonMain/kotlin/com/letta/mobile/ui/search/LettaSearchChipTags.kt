package com.letta.mobile.ui.search

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.search.MessageSearchCapabilities
import com.letta.mobile.data.search.MessageSearchSelection
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags for the shared search's mode and role chips. */
object LettaSearchChipTags {
    fun chip(id: String): String = "letta-search-chip-$id"
}

/** Mode chips when the host supports more than one; role chips whenever the host searches messages. */
fun messageSearchChipRows(
    capabilities: MessageSearchCapabilities,
    selection: MessageSearchSelection,
    onChange: (MessageSearchSelection) -> Unit,
): List<LettaSearchChipRow> = buildList {
    if (capabilities.showsModes()) {
        add(modeChipRow(capabilities, selection, onChange))
    }
    if (capabilities.roles.isNotEmpty()) {
        add(roleChipRow(capabilities, selection, onChange))
    }
}

@Composable
fun LettaSearchChipRows(rows: List<LettaSearchChipRow>, modifier: Modifier = Modifier) {
    if (rows.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        rows.forEach { row -> ChipStrip(row) }
    }
}

@Composable
private fun ChipStrip(row: LettaSearchChipRow) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        row.chips.forEach { chip ->
            FilterChip(
                selected = chip.selected,
                onClick = chip.onClick,
                label = { Text(chip.label) },
                modifier = Modifier.testTag(LettaSearchChipTags.chip(chip.id)),
            )
        }
    }
}

private fun modeChipRow(
    capabilities: MessageSearchCapabilities,
    selection: MessageSearchSelection,
    onChange: (MessageSearchSelection) -> Unit,
): LettaSearchChipRow = LettaSearchChipRow(
    chips = capabilities.modes.map { mode ->
        LettaSearchChip(mode.wire, mode.label, selection.mode == mode) {
            onChange(selection.copy(mode = mode))
        }
    },
    exclusive = true,
)

private fun roleChipRow(
    capabilities: MessageSearchCapabilities,
    selection: MessageSearchSelection,
    onChange: (MessageSearchSelection) -> Unit,
): LettaSearchChipRow = LettaSearchChipRow(
    chips = capabilities.roles.map { role ->
        LettaSearchChip(role.id, role.label, role in selection.roles) {
            onChange(selection.toggled(role))
        }
    },
)
