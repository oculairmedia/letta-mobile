package com.letta.mobile.ui.screens.dashboard

import android.view.HapticFeedbackConstants
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.ui.components.ActionSheet
import com.letta.mobile.ui.components.ActionSheetItem
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaSpacing
import com.letta.mobile.ui.theme.customColors
import kotlinx.collections.immutable.ImmutableList
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState
import java.util.Locale
import androidx.compose.material3.Text

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PinnedAgentCard(
    name: String,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
    isDragging: Boolean = false,
    enableLongPressMenu: Boolean = true,
) {
    var showMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val accentColors = MaterialTheme.customColors
    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "agentTileScale",
    )
    val elevation by animateFloatAsState(
        targetValue = if (isDragging) 8f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "agentTileElevation",
    )
    val clickModifier = if (enableLongPressMenu) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showMenu = true
            },
        )
    } else {
        Modifier.combinedClickable(onClick = onClick)
    }
    Card(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = elevation * density
            }
            .then(clickModifier),
        colors = CardDefaults.cardColors(
            containerColor = accentColors.freshAccentContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                LettaIcons.Agent,
                contentDescription = null,
                tint = accentColors.freshAccent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.screen_home_pinned_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }

    ActionSheet(
        show = showMenu,
        onDismiss = { showMenu = false },
        title = name,
    ) {
        ActionSheetItem(
            text = stringResource(R.string.action_configure_agent),
            icon = LettaIcons.Edit,
            onClick = { showMenu = false; onConfigure() },
        )
        ActionSheetItem(
            text = stringResource(R.string.action_unpin_agent),
            icon = LettaIcons.PinOff,
            onClick = { showMenu = false; onUnpin() },
        )
    }
}

@Composable
internal fun resolveContextualInfo(
    shortcut: DashboardShortcut,
    state: DashboardUiState,
): String? {
    return when (shortcut) {
        DashboardShortcut.AGENTS -> {
            if (state.isAgentCountLoading) "—"
            else state.agentCount?.let { stringResource(R.string.widget_tile_count_format, it) }
        }
        DashboardShortcut.CONVERSATIONS -> {
            if (state.isConversationCountLoading) "—"
            else state.conversationCount?.let {
                val count = stringResource(R.string.widget_tile_count_format, it)
                if (state.isConversationCountApproximate) "$count+" else count
            }
        }
        DashboardShortcut.TOOLS -> {
            if (state.isToolCountLoading) "—"
            else state.toolCount?.let { stringResource(R.string.widget_tile_count_format, it) }
        }
        DashboardShortcut.BLOCKS -> {
            if (state.isBlockCountLoading) "—"
            else state.blockCount?.let { stringResource(R.string.widget_tile_count_format, it) }
        }
        DashboardShortcut.USAGE -> state.usageSummary?.let {
            formatNumber(it.totalTokens) + " tokens"
        } ?: if (state.isUsageLoading) "—" else stringResource(shortcut.descriptionResId)
        DashboardShortcut.FAVORITE_AGENT -> {
            if (state.isPinnedItemsLoading) "—"
            else state.favoriteAgentName ?: stringResource(shortcut.descriptionResId)
        }
        else -> {
            if (shortcut.descriptionResId != 0) {
                stringResource(shortcut.descriptionResId)
            } else null
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DashboardWidgetTile(
    shortcut: DashboardShortcut,
    contextualInfo: String?,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    isDragging: Boolean = false,
    enableLongPressMenu: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }
    val accentColors = MaterialTheme.customColors

    val containerColor = accentColors.freshAccentContainer
    val contentColor = accentColors.freshAccent

    val scale by animateFloatAsState(
        targetValue = if (isDragging) 1.05f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "tileScale",
    )
    val elevation by animateFloatAsState(
        targetValue = if (isDragging) 8f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "tileElevation",
    )

    // letta-mobile-rnyg: the long-press shortcut menu and the reordering grid's
    // long-press-to-drag gesture both fight for the same surface. When the tile
    // is rendered inside ReorderableWidgetGrid, suppress the menu so dragging
    // wins; the parent provides reorder semantics in that mode.
    val clickModifier = if (enableLongPressMenu) {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                showMenu = true
            },
        )
    } else {
        Modifier.combinedClickable(onClick = onClick)
    }

    Card(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = elevation * density
            }
            .then(clickModifier),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = shortcut.icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (contextualInfo != null) {
                Text(
                    text = contextualInfo,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(shortcut.labelResId),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    ActionSheet(
        show = showMenu,
        onDismiss = { showMenu = false },
        title = stringResource(shortcut.labelResId),
    ) {
        ActionSheetItem(
            text = stringResource(R.string.action_unpin_shortcut),
            icon = LettaIcons.PinOff,
            onClick = { showMenu = false; onUnpin() },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReorderableWidgetGrid(
    shortcuts: ImmutableList<DashboardShortcut>,
    state: DashboardUiState,
    onShortcutClick: (DashboardShortcut) -> Unit,
    onUnpinShortcut: (DashboardShortcut) -> Unit,
    onReorder: (List<DashboardShortcut>) -> Unit,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    // letta-mobile-rnyg superseded: the previous hand-rolled Layout +
    // detectDragGesturesAfterLongPress + rect tracking implementation had
    // bugs around cross-row drag and parent-scroll interference. Use
    // sh.calvin.reorderable's LazyGrid integration instead — handles
    // long-press start, swap detection, item displacement animation, and
    // auto-scroll out of the box.
    var currentList by remember(shortcuts) { mutableStateOf(shortcuts.toList()) }
    val view = LocalView.current
    val lazyGridState = rememberLazyGridState()
    val reorderableState = rememberReorderableLazyGridState(lazyGridState) { from, to ->
        currentList = currentList.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    }

    // Commit the new order to the caller when no item is being dragged.
    // The library's onMove fires on every swap; we only persist once the
    // user settles to avoid spammy writes mid-drag.
    LaunchedEffect(reorderableState, currentList) {
        snapshotFlow { reorderableState.isAnyItemDragging }
            .collect { dragging ->
                if (!dragging && currentList != shortcuts.toList()) {
                    onReorder(currentList)
                }
            }
    }

    val gap = LettaSpacing.cardGap
    val rows = (currentList.size + columns - 1) / columns
    // Tile content (icon + optional contextual text + label + padding) lands
    // around ~96-100dp. Pad to 108 so wider text doesn't clip. Items inside
    // each cell still fillMaxWidth and align to a fixed cell height.
    val tileHeight = 108.dp
    val totalHeight = tileHeight * rows + gap * (rows - 1).coerceAtLeast(0)

    LazyVerticalGrid(
        state = lazyGridState,
        columns = GridCells.Fixed(columns),
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
        userScrollEnabled = false,
    ) {
        items(currentList, key = { it.name }) { shortcut ->
            ReorderableItem(reorderableState, key = shortcut.name) { isDragging ->
                DashboardWidgetTile(
                    shortcut = shortcut,
                    contextualInfo = resolveContextualInfo(shortcut, state),
                    onClick = { onShortcutClick(shortcut) },
                    onUnpin = { onUnpinShortcut(shortcut) },
                    isDragging = isDragging,
                    enableLongPressMenu = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tileHeight)
                        .longPressDraggableHandle(
                            onDragStarted = {
                                view.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
                            },
                            onDragStopped = {
                                view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            },
                        ),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReorderablePinnedItemsGrid(
    items: ImmutableList<PinnedItem>,
    state: DashboardUiState,
    onShortcutClick: (DashboardShortcut) -> Unit,
    onUnpinShortcut: (DashboardShortcut) -> Unit,
    onAgentClick: (PinnedAgent) -> Unit,
    onUnpinAgent: (PinnedAgent) -> Unit,
    onConfigureAgent: (PinnedAgent) -> Unit,
    onReorder: (List<String>) -> Unit,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    var currentList by remember(items) { mutableStateOf(items.toList()) }
    val view = LocalView.current
    val lazyGridState = rememberLazyGridState()
    val reorderableState = rememberReorderableLazyGridState(lazyGridState) { from, to ->
        currentList = currentList.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    }

    LaunchedEffect(reorderableState, currentList) {
        snapshotFlow { reorderableState.isAnyItemDragging }
            .collect { dragging ->
                if (!dragging && currentList != items.toList()) {
                    onReorder(currentList.map { it.key })
                }
            }
    }

    val gap = LettaSpacing.cardGap
    val rows = (currentList.size + columns - 1) / columns
    // Tile content (icon + 1-2 text lines + padding) lands around 96-100dp
    // for both shortcut and agent tiles. Pad to 108 so wider text doesn't
    // clip. Items inside each cell fillMaxWidth and align to a fixed cell
    // height — keeping both types visually identical inside the grid.
    val tileHeight = 108.dp
    val totalHeight = tileHeight * rows + gap * (rows - 1).coerceAtLeast(0)

    LazyVerticalGrid(
        state = lazyGridState,
        columns = GridCells.Fixed(columns),
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
        userScrollEnabled = false,
    ) {
        items(currentList, key = { it.key }) { item ->
            ReorderableItem(reorderableState, key = item.key) { isDragging ->
                val tileModifier = Modifier
                    .fillMaxWidth()
                    .height(tileHeight)
                    .longPressDraggableHandle(
                        onDragStarted = {
                            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
                        },
                        onDragStopped = {
                            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                        },
                    )
                when (item) {
                    is PinnedItem.Shortcut -> DashboardWidgetTile(
                        shortcut = item.value,
                        contextualInfo = resolveContextualInfo(item.value, state),
                        onClick = { onShortcutClick(item.value) },
                        onUnpin = { onUnpinShortcut(item.value) },
                        isDragging = isDragging,
                        enableLongPressMenu = false,
                        modifier = tileModifier,
                    )
                    is PinnedItem.Agent -> PinnedAgentCard(
                        name = item.value.name,
                        onClick = { onAgentClick(item.value) },
                        onUnpin = { onUnpinAgent(item.value) },
                        onConfigure = { onConfigureAgent(item.value) },
                        isDragging = isDragging,
                        enableLongPressMenu = false,
                        modifier = tileModifier,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ReorderableAgentGrid(
    pinnedAgents: ImmutableList<PinnedAgent>,
    onAgentClick: (PinnedAgent) -> Unit,
    onUnpinAgent: (PinnedAgent) -> Unit,
    onConfigureAgent: (PinnedAgent) -> Unit,
    onReorder: (List<String>) -> Unit,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    var currentList by remember(pinnedAgents) { mutableStateOf(pinnedAgents.toList()) }
    val view = LocalView.current
    val lazyGridState = rememberLazyGridState()
    val reorderableState = rememberReorderableLazyGridState(lazyGridState) { from, to ->
        currentList = currentList.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        view.performHapticFeedback(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
    }

    LaunchedEffect(reorderableState, currentList) {
        snapshotFlow { reorderableState.isAnyItemDragging }
            .collect { dragging ->
                if (!dragging && currentList != pinnedAgents.toList()) {
                    onReorder(currentList.map { it.id })
                }
            }
    }

    val gap = LettaSpacing.cardGap
    val rows = (currentList.size + columns - 1) / columns
    // Agent tile content (icon + name + subtitle + padding) is similar to
    // shortcut tiles — ~90dp. Pad to 100 for safety.
    val tileHeight = 100.dp
    val totalHeight = tileHeight * rows + gap * (rows - 1).coerceAtLeast(0)

    LazyVerticalGrid(
        state = lazyGridState,
        columns = GridCells.Fixed(columns),
        modifier = modifier
            .fillMaxWidth()
            .height(totalHeight),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalArrangement = Arrangement.spacedBy(gap),
        userScrollEnabled = false,
    ) {
        items(currentList, key = { it.id }) { agent ->
            ReorderableItem(reorderableState, key = agent.id) { isDragging ->
                PinnedAgentCard(
                    name = agent.name,
                    onClick = { onAgentClick(agent) },
                    onUnpin = { onUnpinAgent(agent) },
                    onConfigure = { onConfigureAgent(agent) },
                    isDragging = isDragging,
                    enableLongPressMenu = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tileHeight)
                        .longPressDraggableHandle(
                            onDragStarted = {
                                view.performHapticFeedback(HapticFeedbackConstants.GESTURE_START)
                            },
                            onDragStopped = {
                                view.performHapticFeedback(HapticFeedbackConstants.GESTURE_END)
                            },
                        ),
                )
            }
        }
    }
}

@Composable
internal fun CollapsibleSectionHeader(
    title: String,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    topPadding: Boolean = false,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onToggle)
            .padding(top = if (topPadding) 8.dp else 0.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "($count)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        Icon(
            imageVector = if (expanded) LettaIcons.ExpandLess else LettaIcons.ExpandMore,
            contentDescription = if (expanded) "Collapse" else "Expand",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

internal fun formatNumber(value: Int): String = String.format(Locale.US, "%,d", value)
