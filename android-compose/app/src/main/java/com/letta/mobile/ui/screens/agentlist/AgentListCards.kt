package com.letta.mobile.ui.screens.agentlist

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ca.oculair.meridian.R
import com.letta.mobile.data.model.Agent
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.components.LettaCardDefaults
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.mascot.AgentAvatar
import com.letta.mobile.ui.navigation.agentAvatarSharedElementKey
import com.letta.mobile.ui.navigation.optionalSharedElement
import com.letta.mobile.ui.theme.listItemHeadline
import com.letta.mobile.ui.theme.listItemMetadata
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.theme.LettaDimens

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
/** The agent's tile in the list: the shared [AgentAvatar] - its mascot, or its initial (letta-mobile-8jtf3). */
private val AgentTileSize = LettaDimens.Orb.railSlotHeight
private val CompactAgentTileSize = LettaDimens.Space.xxl

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
    val blockCount = agent.coreBlocks.size
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
                .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentAvatar(
                agentId = agent.id.value,
                name = agent.name,
                size = AgentTileSize,
                modifier = Modifier.optionalSharedElement(agentAvatarSharedElementKey(agent.id.value)),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
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
            modifier = Modifier.padding(LettaDimens.Space.md),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = LettaIcons.Share,
                contentDescription = null,
                modifier = Modifier.size(LettaIconSizing.Toolbar),
            )
            Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
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

internal data class AgentCardBindModel(
    val agent: Agent,
    val isFavorite: Boolean,
    val isPinned: Boolean,
    val contextualActionsEnabled: Boolean,
    val onClick: () -> Unit,
    val onLongPress: () -> Unit,
    val onDelete: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onTogglePinned: () -> Unit,
)

@Composable
internal fun AgentCard(model: AgentCardBindModel, modifier: Modifier = Modifier) {
    AgentCard(
        agent = model.agent,
        isFavorite = model.isFavorite,
        isPinned = model.isPinned,
        onClick = model.onClick,
        onLongPress = model.onLongPress,
        onDelete = model.onDelete,
        onToggleFavorite = model.onToggleFavorite,
        onTogglePinned = model.onTogglePinned,
        modifier = modifier,
        contextualActionsEnabled = model.contextualActionsEnabled,
    )
}

@Composable
internal fun CompactAgentCard(model: AgentCardBindModel, modifier: Modifier = Modifier) {
    CompactAgentCard(
        agent = model.agent,
        isFavorite = model.isFavorite,
        isPinned = model.isPinned,
        onClick = model.onClick,
        onLongPress = model.onLongPress,
        onDelete = model.onDelete,
        onToggleFavorite = model.onToggleFavorite,
        onTogglePinned = model.onTogglePinned,
        modifier = modifier,
        contextualActionsEnabled = model.contextualActionsEnabled,
    )
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
    val view = LocalView.current

    val toolCount = agent.tools.size
    val blockCount = agent.coreBlocks.size
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
                        HapticEffects.longPress(haptic, view)
                        showContextMenu = true
                    }
                } else {
                    null
                },
            ),
        shape = RoundedCornerShape(LettaDimens.Radius.lg),
        color = if (isFavorite) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            LettaCardDefaults.listContainerColor
        },
        tonalElevation = LettaDimens.Space.xs,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AgentAvatar(
                agentId = agent.id.value,
                name = agent.name,
                size = AgentTileSize,
                modifier = Modifier.optionalSharedElement(agentAvatarSharedElementKey(agent.id.value)),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
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
                                .padding(start = LettaDimens.Space.sm)
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
    val view = LocalView.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(108.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (contextualActionsEnabled) {
                    {
                        HapticEffects.longPress(haptic, view)
                        showContextMenu = true
                    }
                } else {
                    null
                },
            ),
        shape = RoundedCornerShape(LettaDimens.Radius.lg),
        color = if (isFavorite) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            LettaCardDefaults.listContainerColor
        },
        tonalElevation = LettaDimens.Space.xs,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(LettaDimens.Space.md),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AgentAvatar(
                    agentId = agent.id.value,
                    name = agent.name,
                    size = CompactAgentTileSize,
                    modifier = Modifier.optionalSharedElement(agentAvatarSharedElementKey(agent.id.value)),
                )
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
                            .padding(start = if (isFavorite) LettaDimens.Space.sm else 0.dp)
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
