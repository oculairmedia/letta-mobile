package com.letta.mobile.feature.editagent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal data class SectionIndexEntry(
    val anchorKey: String,
    val labelRes: Int,
)

internal fun sectionIndexEntries(): List<SectionIndexEntry> = listOf(
    SectionIndexEntry(SectionAnchors.BASICS, R.string.screen_agent_edit_section_basics),
    SectionIndexEntry(SectionAnchors.MODELS, R.string.screen_agent_edit_section_models),
    SectionIndexEntry(SectionAnchors.MEMORY, R.string.screen_agent_edit_section_memory),
    SectionIndexEntry(SectionAnchors.TOOLS, R.string.screen_agent_edit_section_tools),
    SectionIndexEntry(SectionAnchors.RUNTIME, R.string.screen_agent_edit_section_runtime),
    SectionIndexEntry(SectionAnchors.ADVANCED, R.string.screen_agent_edit_section_advanced),
    SectionIndexEntry(SectionAnchors.DANGER, R.string.screen_create_project_danger_zone_title),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SectionIndexSheet(
    onDismiss: () -> Unit,
    onSelect: (anchorKey: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        SectionIndexSheetContent(onSelect = onSelect)
    }
}

@Composable
private fun SectionIndexSheetContent(onSelect: (anchorKey: String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.screen_agent_edit_jump_to_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        sectionIndexEntries().forEach { entry ->
            SectionIndexSheetRow(
                entry = entry,
                onSelect = onSelect,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun SectionIndexSheetRow(
    entry: SectionIndexEntry,
    onSelect: (anchorKey: String) -> Unit,
) {
    val isDanger = entry.anchorKey == SectionAnchors.DANGER
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(entry.anchorKey) },
        headlineContent = {
            Text(
                text = stringResource(entry.labelRes),
                color = if (isDanger) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
    )
}
