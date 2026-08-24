package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.oculair.meridian.R
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.tags.TagDrillInEntityType
import com.letta.mobile.ui.tags.TagDrillInItem
import com.letta.mobile.ui.tags.TagDrillInUiState
import com.letta.mobile.ui.theme.dialogSectionHeading
import com.letta.mobile.ui.theme.listItemMetadata
import com.letta.mobile.ui.theme.listItemSupporting

@Composable
fun TagDrillInDialog(
    state: TagDrillInUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeTag = state.activeTag ?: return

    AdaptiveDialog(
        title = stringResource(R.string.screen_tag_drill_in_title, activeTag),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        when {
            state.isLoading -> {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = stringResource(R.string.screen_tag_drill_in_loading),
                        style = MaterialTheme.typography.listItemSupporting,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.items.isEmpty() -> {
                Text(
                    text = stringResource(R.string.screen_tag_drill_in_empty),
                    style = MaterialTheme.typography.listItemSupporting,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                val itemsByType = remember(state.items) {
                    state.items.groupBy { it.entityType }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TagDrillInEntityType.entries.forEach { entityType ->
                        val itemsForType = itemsByType[entityType] ?: emptyList()
                        if (itemsForType.isNotEmpty()) {
                            item(key = "header_${entityType.name}") {
                                Text(
                                    text = sectionTitle(entityType),
                                    style = MaterialTheme.typography.dialogSectionHeading,
                                )
                            }
                            items(itemsForType, key = { "${it.entityType}:${it.id}" }) { item ->
                                TagDrillInItemCard(item = item)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagDrillInItemCard(item: TagDrillInItem) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = icon(item.entityType),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            item.supportingText?.takeIf { it.isNotBlank() }?.let { supportingText ->
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.listItemSupporting,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            item.metadataText?.takeIf { it.isNotBlank() }?.let { metadataText ->
                Text(
                    text = metadataText,
                    style = MaterialTheme.typography.listItemMetadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (item.otherTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.otherTags.forEach { tag ->
                        AssistChip(
                            onClick = {},
                            enabled = false,
                            label = { Text(tag) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sectionTitle(entityType: TagDrillInEntityType): String {
    return when (entityType) {
        TagDrillInEntityType.AGENT -> stringResource(R.string.screen_tag_drill_in_agents_section)
        TagDrillInEntityType.TOOL -> stringResource(R.string.screen_tag_drill_in_tools_section)
        TagDrillInEntityType.TEMPLATE -> stringResource(R.string.screen_tag_drill_in_templates_section)
        TagDrillInEntityType.STEP -> stringResource(R.string.screen_tag_drill_in_steps_section)
    }
}

private fun icon(entityType: TagDrillInEntityType) = when (entityType) {
    TagDrillInEntityType.AGENT -> LettaIcons.Agent
    TagDrillInEntityType.TOOL -> LettaIcons.Tool
    TagDrillInEntityType.TEMPLATE -> LettaIcons.Apps
    TagDrillInEntityType.STEP -> LettaIcons.ChatOutline
}
