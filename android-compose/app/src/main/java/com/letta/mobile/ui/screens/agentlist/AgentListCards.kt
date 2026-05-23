package com.letta.mobile.ui.screens.agentlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.navigation.agentAvatarSharedElementKey
import com.letta.mobile.ui.navigation.optionalSharedElement
import com.letta.mobile.ui.theme.LettaCornerRadius
import com.letta.mobile.ui.theme.LettaElevation
import com.letta.mobile.ui.theme.LettaSizing
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.listItemHeadline
import com.letta.mobile.ui.theme.listItemMetadata
import com.letta.mobile.ui.theme.listItemSupporting

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun FavoriteAgentCard(
    agent: Agent,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onUnfavorite: () -> Unit,
    modifier: Modifier = Modifier,
    contextualActionsEnabled: Boolean = true,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    val toolCount = agent.tools.size
    val blockCount = agent.blocks.size
    val supporting = agent.description
        ?.takeIf { it.isNotBlank() }
        ?: agent.model
        ?: "No model"

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = LettaCardDefaults.prominentListShape,
        colors = LettaCardDefaults.listCardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LettaSpacing.listItemHorizontal, vertical = LettaSpacing.listItemVertical),
            horizontalArrangement = Arrangement.spacedBy(LettaSpacing.listItemVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .size(LettaSizing.prominentAvatar)
                    .optionalSharedElement(agentAvatarSharedElementKey(agent.id.value)),
                shape = RoundedCornerShape(LettaCornerRadius.medium),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = LettaIcons.Star,
                        contentDescription = "Favorite",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(LettaIconSizing.ListAvatar),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LettaSpacing.extraSmall),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.listItemHeadline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = LettaIcons.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(LettaIconSizing.Inline),
                    )
                }

                Text(
                    text = supporting,
                    style = MaterialTheme.typography.listItemSupporting,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$toolCount ${stringResource(R.string.common_tools)} - $blockCount memory",
                    style = MaterialTheme.typography.listItemMetadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (contextualActionsEnabled) {
                IconButton(onClick = { showContextMenu = true }) {
                    Icon(LettaIcons.MoreVert, contentDescription = null)
                }
            }
        }
    }

    ActionSheet(
        show = showContextMenu,
        onDismiss = { showContextMenu = false },
        title = agent.name,
    ) {
        ActionSheetItem(
            text = stringResource(R.string.action_edit),
            icon = LettaIcons.Edit,
            onClick = { showContextMenu = false; onEdit() },
        )
        ActionSheetItem(
            text = "Remove Favorite",
            icon = LettaIcons.Star,
            onClick = { showContextMenu = false; onUnfavorite() },
        )
    }
}

@Composable
internal fun ShareContentPreviewCard(
    content: String,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(LettaSpacing.innerPaddingSmall),
            horizontalArrangement = Arrangement.spacedBy(LettaSpacing.iconGap),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = LettaIcons.Share,
                contentDescription = null,
                modifier = Modifier.size(LettaIconSizing.Toolbar),
            )
            Column(verticalArrangement = Arrangement.spacedBy(LettaSpacing.extraSmall)) {
                Text(
                    text = "Pick an agent to send this content",
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AgentCard(
    agent: Agent,
    isFavorite: Boolean = false,
    isPinned: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePinned: () -> Unit = {},
    modifier: Modifier = Modifier,
    contextualActionsEnabled: Boolean = true,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val toolCount = agent.tools.size
    val blockCount = agent.blocks.size
    val supporting = agent.description
        ?.takeIf { it.isNotBlank() }
        ?: agent.model
        ?: "No model"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (contextualActionsEnabled) {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showContextMenu = true
                    }
                } else {
                    null
                },
            ),
            shape = LettaCardDefaults.prominentListShape,
        color = if (isFavorite) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            LettaCardDefaults.listContainerColor
        },
        tonalElevation = LettaElevation.medium,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LettaSpacing.listItemHorizontal, vertical = LettaSpacing.listItemVertical),
            horizontalArrangement = Arrangement.spacedBy(LettaSpacing.listItemVertical),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier
                    .size(LettaSizing.prominentAvatar)
                    .optionalSharedElement(agentAvatarSharedElementKey(agent.id.value)),
                shape = RoundedCornerShape(LettaCornerRadius.medium),
                color = if (isPinned) {
                    MaterialTheme.colorScheme.tertiaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = LettaIcons.Agent,
                        contentDescription = null,
                        tint = if (isPinned) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(LettaIconSizing.ListAvatar),
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LettaSpacing.extraSmall),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = agent.name,
                        style = MaterialTheme.typography.listItemHeadline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (isFavorite) {
                        Icon(
                            imageVector = LettaIcons.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(LettaIconSizing.Inline),
                        )
                    }
                    if (isPinned) {
                        Icon(
                            imageVector = LettaIcons.Pin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .padding(start = LettaSpacing.compact)
                                .size(LettaIconSizing.Inline),
                        )
                    }
                }

                Text(
                    text = supporting,
                    style = MaterialTheme.typography.listItemSupporting,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "$toolCount ${stringResource(R.string.common_tools)} - $blockCount memory",
                    style = MaterialTheme.typography.listItemMetadata,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (contextualActionsEnabled) {
                IconButton(onClick = { showContextMenu = true }) {
                    Icon(LettaIcons.MoreVert, contentDescription = null)
                }
            }
        }
    }

    ActionSheet(
        show = showContextMenu,
        onDismiss = { showContextMenu = false },
        title = agent.name,
    ) {
        ActionSheetItem(
            text = if (isFavorite) "Remove Favorite" else "Set as Favorite",
            icon = LettaIcons.Star,
            onClick = { showContextMenu = false; onToggleFavorite() },
        )
        ActionSheetItem(
            text = if (isPinned) "Unpin from Homepage" else "Pin to Homepage",
            icon = if (isPinned) LettaIcons.PinOff else LettaIcons.Pin,
            onClick = { showContextMenu = false; onTogglePinned() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_edit),
            icon = LettaIcons.Edit,
            onClick = { showContextMenu = false; onLongPress() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_delete),
            icon = LettaIcons.Delete,
            onClick = { showContextMenu = false; showDeleteDialog = true },
            destructive = true,
        )
    }

    ConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.screen_agents_dialog_delete_title),
        message = stringResource(R.string.screen_agents_dialog_delete_confirm, agent.name),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showDeleteDialog = false; onDelete() },
        onDismiss = { showDeleteDialog = false },
        destructive = true,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CompactAgentCard(
    agent: Agent,
    isFavorite: Boolean = false,
    isPinned: Boolean = false,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePinned: () -> Unit = {},
    modifier: Modifier = Modifier,
    contextualActionsEnabled: Boolean = true,
) {
    var showContextMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(LettaSizing.compactAgentCardHeight)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (contextualActionsEnabled) {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        showContextMenu = true
                    }
                } else {
                    null
                },
            ),
        shape = LettaCardDefaults.prominentListShape,
        color = if (isFavorite) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            LettaCardDefaults.listContainerColor
        },
        tonalElevation = LettaElevation.medium,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(LettaSpacing.innerPaddingSmall),
            verticalArrangement = Arrangement.spacedBy(LettaSpacing.compact),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier
                        .size(LettaSizing.compactAvatar)
                        .optionalSharedElement(agentAvatarSharedElementKey(agent.id.value)),
                    shape = RoundedCornerShape(LettaCornerRadius.listCompactAvatar),
                    color = if (isPinned) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = LettaIcons.Agent,
                            contentDescription = null,
                            tint = if (isPinned) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            modifier = Modifier.size(LettaIconSizing.CompactAvatar),
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                if (isFavorite) {
                    Icon(
                        imageVector = LettaIcons.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(LettaIconSizing.Inline),
                    )
                }
                if (isPinned) {
                    Icon(
                        imageVector = LettaIcons.Pin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier
                            .padding(start = if (isFavorite) LettaSpacing.compact else LettaSpacing.none)
                            .size(LettaIconSizing.Inline),
                    )
                }
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = agent.name,
                style = MaterialTheme.typography.listItemHeadline,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = agent.model ?: "No model",
                style = MaterialTheme.typography.listItemMetadata,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    ActionSheet(
        show = showContextMenu,
        onDismiss = { showContextMenu = false },
        title = agent.name,
    ) {
        ActionSheetItem(
            text = if (isFavorite) "Remove Favorite" else "Set as Favorite",
            icon = LettaIcons.Star,
            onClick = { showContextMenu = false; onToggleFavorite() },
        )
        ActionSheetItem(
            text = if (isPinned) "Unpin from Homepage" else "Pin to Homepage",
            icon = if (isPinned) LettaIcons.PinOff else LettaIcons.Pin,
            onClick = { showContextMenu = false; onTogglePinned() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_edit),
            icon = LettaIcons.Edit,
            onClick = { showContextMenu = false; onLongPress() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_delete),
            icon = LettaIcons.Delete,
            onClick = { showContextMenu = false; showDeleteDialog = true },
            destructive = true,
        )
    }

    ConfirmDialog(
        show = showDeleteDialog,
        title = stringResource(R.string.screen_agents_dialog_delete_title),
        message = stringResource(R.string.screen_agents_dialog_delete_confirm, agent.name),
        confirmText = stringResource(R.string.action_delete),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showDeleteDialog = false; onDelete() },
        onDismiss = { showDeleteDialog = false },
        destructive = true,
    )
}
