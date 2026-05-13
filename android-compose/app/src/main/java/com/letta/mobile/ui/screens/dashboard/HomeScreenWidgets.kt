package com.letta.mobile.ui.screens.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.IntOffset
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
import java.util.Locale
import kotlin.math.roundToInt
import androidx.compose.material3.Text

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PinnedAgentCard(
    name: String,
    onClick: () -> Unit,
    onUnpin: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current

    val accentColors = MaterialTheme.customColors
    Card(
        modifier = modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    showMenu = true
                },
            ),
        colors = CardDefaults.cardColors(
            containerColor = accentColors.freshAccentContainer,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
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
            if (state.isPinnedAgentsLoading) "—" 
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
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
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
    var currentList by remember(shortcuts) { mutableStateOf(shortcuts.toList()) }
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val itemRects = remember { mutableStateMapOf<Int, Rect>() }
    val haptic = LocalHapticFeedback.current

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                return if (draggingIndex != null) available else Offset.Zero
            }
        }
    }

    val gap = LettaSpacing.cardGap

    Layout(
        content = {
            currentList.forEachIndexed { index, shortcut ->
                key(shortcut) {
                    val isDragging = draggingIndex == index

                    // Track previous slot for easing animation
                    var previousSlot by remember { mutableIntStateOf(index) }
                    val slotOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

                    LaunchedEffect(index) {
                        if (previousSlot != index && draggingIndex != index) {
                            // Item was displaced — animate from old position to new
                            val cols = columns
                            val oldCol = previousSlot % cols
                            val newCol = index % cols
                            val oldRow = previousSlot / cols
                            val newRow = index / cols

                            // We compute pixel delta using measured rects if available
                            val oldRect = itemRects[previousSlot]
                            val newRect = itemRects[index]
                            if (oldRect != null && newRect != null) {
                                val delta = Offset(
                                    oldRect.left - newRect.left,
                                    oldRect.top - newRect.top,
                                )
                                slotOffset.snapTo(delta)
                                slotOffset.animateTo(
                                    targetValue = Offset.Zero,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMediumLow,
                                    ),
                                )
                            }
                        }
                        previousSlot = index
                    }

                    Box(
                        modifier = Modifier
                            .then(if (isDragging) Modifier.zIndex(10f) else Modifier)
                            .then(
                                if (isDragging) {
                                    Modifier.offset {
                                        IntOffset(
                                            dragOffset.x.roundToInt(),
                                            dragOffset.y.roundToInt(),
                                        )
                                    }
                                } else {
                                    Modifier.offset {
                                        IntOffset(
                                            slotOffset.value.x.roundToInt(),
                                            slotOffset.value.y.roundToInt(),
                                        )
                                    }
                                },
                            )
                            .pointerInput(index) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        draggingIndex = index
                                        dragOffset = Offset.Zero
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffset += Offset(dragAmount.x, dragAmount.y)

                                        // letta-mobile-rnyg: after the first swap the dragged
                                        // item lives at draggingIndex, not the original `index`
                                        // that scoped this pointerInput. Always derive the
                                        // current slot from draggingIndex so subsequent
                                        // crossings reorder the right item.
                                        val currentIndex = draggingIndex ?: index
                                        val draggedRect = itemRects[currentIndex] ?: return@detectDragGesturesAfterLongPress
                                        val draggedCenter = draggedRect.center + dragOffset
                                        val targetIndex = itemRects.entries
                                            .firstOrNull { (i, rect) ->
                                                i != currentIndex && rect.contains(draggedCenter)
                                            }?.key

                                        if (targetIndex != null && targetIndex != currentIndex) {
                                            val oldRect = itemRects[currentIndex] ?: return@detectDragGesturesAfterLongPress
                                            val newRect = itemRects[targetIndex] ?: return@detectDragGesturesAfterLongPress

                                            currentList = currentList.toMutableList().apply {
                                                val item = removeAt(currentIndex)
                                                add(targetIndex, item)
                                            }
                                            draggingIndex = targetIndex
                                            dragOffset += Offset(
                                                oldRect.left - newRect.left,
                                                oldRect.top - newRect.top,
                                            )
                                        }
                                    },
                                    onDragEnd = {
                                        draggingIndex = null
                                        dragOffset = Offset.Zero
                                        onReorder(currentList)
                                    },
                                    onDragCancel = {
                                        draggingIndex = null
                                        dragOffset = Offset.Zero
                                        currentList = shortcuts.toList()
                                    },
                                )
                            },
                    ) {
                        DashboardWidgetTile(
                            shortcut = shortcut,
                            contextualInfo = resolveContextualInfo(shortcut, state),
                            onClick = { onShortcutClick(shortcut) },
                            onUnpin = { onUnpinShortcut(shortcut) },
                            isDragging = isDragging,
                            enableLongPressMenu = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .nestedScroll(nestedScrollConnection),
    ) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val totalGapWidth = gapPx * (columns - 1)
        val cellWidth = (constraints.maxWidth - totalGapWidth) / columns
        val cellConstraints = constraints.copy(
            minWidth = cellWidth,
            maxWidth = cellWidth,
            minHeight = 0,
        )

        val placeables = measurables.map { it.measure(cellConstraints) }
        val rows = placeables.chunked(columns)
        val rowHeights = rows.map { row -> row.maxOf { it.height } }
        val totalHeight = rowHeights.sum() + gapPx * (rowHeights.size - 1).coerceAtLeast(0)

        layout(constraints.maxWidth, totalHeight) {
            var y = 0
            rows.forEachIndexed { rowIndex, row ->
                var x = 0
                row.forEachIndexed { colIndex, placeable ->
                    val globalIndex = rowIndex * columns + colIndex
                    itemRects[globalIndex] = Rect(
                        Offset(x.toFloat(), y.toFloat()),
                        Size(cellWidth.toFloat(), rowHeights[rowIndex].toFloat()),
                    )
                    placeable.placeRelative(x, y)
                    x += cellWidth + gapPx
                }
                y += rowHeights[rowIndex] + gapPx
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
