package com.letta.mobile.ui.shell.pages.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.home.HomePinnedItem
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyGridState

/**
 * The pinned grid: shortcuts and agents the user chose, in their order. Long-press and drag
 * reorders (touch and mouse alike); "Edit" reveals each tile's unpin action and an add tile listing
 * the shortcuts this host can still pin.
 */
internal fun LazyListScope.homePinnedSection(page: HomePageScope) {
    val state = page.state
    if (!state.pinsLoaded) return
    if (state.pinnedItems.isEmpty() && state.unpinnedShortcuts.isEmpty()) return
    item(key = "pinned-header") { PinnedHeader(page) }
    item(key = "pinned-grid") { PinnedGrid(page) }
}

@Composable
private fun PinnedHeader(page: HomePageScope) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HomeSectionLabel(PINNED_LABEL, Modifier.weight(1f))
        TextButton(onClick = { page.onEditingPinsChange(!page.editingPins) }, modifier = Modifier.testTag(HomePageTags.EDIT_PINS)) {
            Text(if (page.editingPins) DONE_LABEL else EDIT_LABEL)
        }
    }
}

@Composable
private fun PinnedGrid(page: HomePageScope) {
    val items = page.state.pinnedItems
    var order by remember(items) { mutableStateOf(items) }
    val gridState = rememberLazyGridState()
    val reorderState = rememberReorderableLazyGridState(gridState) { from, to ->
        if (from.index in order.indices && to.index in order.indices) {
            order = order.toMutableList().apply { add(to.index, removeAt(from.index)) }
        }
    }
    val commitOrder = { if (order != items) page.actions.reorderPins(order.map { it.key }) }
    val columns = if (page.wide) WIDE_PIN_COLUMNS else COMPACT_PIN_COLUMNS
    val showAdd = page.editingPins && page.state.unpinnedShortcuts.isNotEmpty()
    val cells = order.size + if (showAdd) 1 else 0
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxWidth().height(gridHeight(cells, columns)).testTag(HomePageTags.PINNED_GRID),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        userScrollEnabled = false,
    ) {
        items(order, key = { it.key }) { item ->
            ReorderableItem(reorderState, key = item.key) { dragging ->
                PinnedTile(
                    item = item,
                    page = page,
                    dragging = dragging,
                    modifier = Modifier.longPressDraggableHandle(onDragStopped = { commitOrder() }),
                )
            }
        }
        if (showAdd) item(key = ADD_KEY) { AddPinTile(page) }
    }
}

private fun gridHeight(cells: Int, columns: Int): Dp {
    val rows = (cells + columns - 1) / columns
    return TileHeight * rows + LettaDimens.Space.md * (rows - 1).coerceAtLeast(0)
}

@Composable
private fun PinnedTile(item: HomePinnedItem, page: HomePageScope, dragging: Boolean, modifier: Modifier) {
    val scale by animateFloatAsState(
        targetValue = if (dragging) DRAG_SCALE else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pinScale",
    )
    val tile = item.tileContent(page)
    Box(modifier.fillMaxWidth().height(TileHeight).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Surface(
            onClick = tile.onClick,
            enabled = !page.editingPins,
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = if (dragging) DRAG_ELEVATION else 0.dp,
            modifier = Modifier.fillMaxSize().testTag(HomePageTags.pin(item.key)),
        ) {
            TileBody(tile)
        }
        if (page.editingPins) TileEditActions(item, page, Modifier.align(Alignment.TopEnd))
    }
}

private class TileContent(val icon: ImageVector, val headline: String?, val label: String, val onClick: () -> Unit)

private fun HomePinnedItem.tileContent(page: HomePageScope): TileContent = when (this) {
    is HomePinnedItem.Shortcut -> TileContent(shortcut.icon, page.state.shortcutInfo(shortcut), shortcut.label) {
        page.navigation.onOpenShortcut(shortcut)
    }
    is HomePinnedItem.Agent -> TileContent(LettaIcons.Agent, agent.name, PINNED_AGENT_LABEL) {
        page.navigation.onOpenAgent(agent.id)
    }
}

@Composable
private fun TileBody(tile: TileContent) {
    Column(
        modifier = Modifier.fillMaxSize().padding(LettaDimens.Space.md),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(tile.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(LettaDimens.Control.iconButtonSm))
        Spacer(Modifier.height(LettaDimens.Space.xs))
        tile.headline?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(tile.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TileEditActions(item: HomePinnedItem, page: HomePageScope, modifier: Modifier) {
    Row(modifier) {
        val configure = page.navigation.onConfigureAgent
        if (item is HomePinnedItem.Agent && configure != null) {
            IconButton(onClick = { configure(item.agent.id) }, modifier = Modifier.testTag(HomePageTags.configure(item.agent.id))) {
                Icon(LettaIcons.Edit, contentDescription = CONFIGURE_LABEL, modifier = Modifier.size(LettaDimens.Control.iconSm))
            }
        }
        IconButton(onClick = { item.unpin(page) }, modifier = Modifier.testTag(HomePageTags.unpin(item.key))) {
            Icon(LettaIcons.PinOff, contentDescription = UNPIN_LABEL, modifier = Modifier.size(LettaDimens.Control.iconSm))
        }
    }
}

private fun HomePinnedItem.unpin(page: HomePageScope) = when (this) {
    is HomePinnedItem.Shortcut -> page.actions.setShortcutPinned(shortcut, pinned = false)
    is HomePinnedItem.Agent -> page.actions.setAgentPinned(agent.id, pinned = false)
}

@Composable
private fun AddPinTile(page: HomePageScope) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().height(TileHeight)) {
        Surface(
            onClick = { open = true },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxSize().testTag(HomePageTags.ADD_PIN),
        ) {
            TileBody(TileContent(LettaIcons.Add, null, ADD_LABEL) { open = true })
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            page.state.unpinnedShortcuts.forEach { shortcut ->
                DropdownMenuItem(
                    text = { Text(shortcut.label) },
                    leadingIcon = { Icon(shortcut.icon, contentDescription = null) },
                    onClick = {
                        open = false
                        page.actions.setShortcutPinned(shortcut, pinned = true)
                    },
                    modifier = Modifier.testTag(HomePageTags.addShortcut(shortcut)),
                )
            }
        }
    }
}

/** The icon each shortcut tile and menu entry draws. */
internal val HomeShortcut.icon: ImageVector
    get() = when (this) {
        HomeShortcut.CONVERSATIONS -> LettaIcons.Chat
        HomeShortcut.AGENTS -> LettaIcons.People
        HomeShortcut.TOOLS -> LettaIcons.Tool
        HomeShortcut.BLOCKS -> LettaIcons.ViewModule
        HomeShortcut.TEMPLATES -> LettaIcons.Dashboard
        HomeShortcut.ARCHIVES -> LettaIcons.Storage
        HomeShortcut.FOLDERS -> LettaIcons.ManageSearch
        HomeShortcut.GROUPS -> LettaIcons.ForkRight
        HomeShortcut.PROVIDERS, HomeShortcut.MCP_SERVERS -> LettaIcons.Cloud
        HomeShortcut.IDENTITIES -> LettaIcons.AccountCircle
        HomeShortcut.SCHEDULES, HomeShortcut.JOBS -> LettaIcons.AccessTime
        HomeShortcut.RUNS, HomeShortcut.MESSAGE_BATCHES -> LettaIcons.ChatOutline
        HomeShortcut.BOT_SETTINGS -> LettaIcons.Agent
        HomeShortcut.PROJECTS -> LettaIcons.Apps
        HomeShortcut.MODELS -> LettaIcons.Sparkles
        HomeShortcut.USAGE, HomeShortcut.TELEMETRY -> LettaIcons.Database
        HomeShortcut.FAVORITE_AGENT -> LettaIcons.Star
        HomeShortcut.SETTINGS -> LettaIcons.Settings
        HomeShortcut.SYSTEM_ACCESS -> LettaIcons.Key
        HomeShortcut.ABOUT -> LettaIcons.Info
    }

private const val PINNED_LABEL = "Pinned"
private const val EDIT_LABEL = "Edit"
private const val DONE_LABEL = "Done"
private const val ADD_LABEL = "Add shortcut"
private const val PINNED_AGENT_LABEL = "Pinned agent"
private const val UNPIN_LABEL = "Unpin from Home"
private const val CONFIGURE_LABEL = "Configure agent"
private const val ADD_KEY = "add-pin"
private const val WIDE_PIN_COLUMNS = 6
private const val COMPACT_PIN_COLUMNS = 3
private const val DRAG_SCALE = 1.05f
private val DRAG_ELEVATION = 8.dp
private val TileHeight = 108.dp
