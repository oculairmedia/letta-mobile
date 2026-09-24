package com.letta.mobile.ui.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.Download
import com.composables.icons.lucide.EllipsisVertical
import com.composables.icons.lucide.FileJson
import com.composables.icons.lucide.History
import com.composables.icons.lucide.Import
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize
import com.composables.icons.lucide.Redo2
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.Undo2
import com.composables.icons.lucide.ZoomIn
import com.composables.icons.lucide.ZoomOut
import com.letta.mobile.data.canvas.CanvasBackgroundPattern
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The board's chrome, in the layout whiteboard tools converge on: a small title pill top-left,
 * an actions pill top-right with the rarely used commands folded into an overflow menu, a zoom
 * pill bottom-right, and a quiet status line bottom-left. The board itself stays uncovered.
 */

/** Top-left: back (when the host navigates) and the canvas title with its revision. */
@Composable
internal fun CanvasTitlePill(
    title: String,
    revision: Long?,
    onNavigateBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** A phone: the title gives up width so the actions pill fits on the same row. */
    compact: Boolean = false,
) {
    ChromePill(modifier = modifier) {
        if (onNavigateBack != null) {
            PillIconButton(Lucide.ChevronLeft, "Back", onClick = onNavigateBack)
        }
        Column(modifier = Modifier.padding(start = if (onNavigateBack == null) LettaDimens.Space.md else LettaDimens.Space.hair, end = LettaDimens.Space.lg)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (compact) COMPACT_TITLE_WIDTH else 220.dp),
            )
            if (revision != null) {
                Text(
                    text = "rev $revision",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** What the overflow menu offers; the workspace supplies the actions, the pill only lists them. */
internal data class CanvasMenuActions(
    val onImportBuildCycle: () -> Unit,
    val onImportDailyLoop: () -> Unit,
    val onExportJson: () -> Unit,
    val onExportSvg: () -> Unit,
    val onClear: () -> Unit,
)

/** The board's background as the menu shows and changes it: colour, then pattern, spacing, tint. */
internal data class CanvasBackgroundActions(
    val color: Color,
    val onColor: (Color) -> Unit,
    val pattern: CanvasBackgroundPattern,
    val onPattern: (CanvasBackgroundPattern) -> Unit,
)

/** How far in or out the board is and the ways to change it. */
internal data class CanvasZoom(
    val scalePercent: Int,
    val onZoomOut: () -> Unit,
    val onZoomIn: () -> Unit,
    /** Fit to content. */
    val onReset: () -> Unit,
    /** Back to 100%, on a double-click of the percentage. */
    val onActualSize: () -> Unit = {},
)

/** The board's undo history as the compact actions pill offers it. */
internal data class CanvasUndoActions(
    val canUndo: Boolean,
    val canRedo: Boolean,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
)

/**
 * Top-right: zoom, then history and share as icons, everything else behind the overflow.
 *
 * With [undo] (the compact layout) the pill is undo, redo, share and the overflow instead: the
 * tool bar at the foot has no room for undo, and on a phone zoom is a pinch, so the zoom buttons
 * and history move into the menu.
 */
@Composable
internal fun CanvasActionsPill(
    zoom: CanvasZoom,
    checkpointCount: Int?,
    onHistory: (() -> Unit)?,
    onShare: (() -> Unit)?,
    menu: CanvasMenuActions,
    background: CanvasBackgroundActions,
    modifier: Modifier = Modifier,
    undo: CanvasUndoActions? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val compact = undo != null
    ChromePill(modifier = modifier) {
        if (undo != null) {
            PillIconButton(Lucide.Undo2, "Undo", enabled = undo.canUndo, onClick = undo.onUndo)
            PillIconButton(Lucide.Redo2, "Redo", enabled = undo.canRedo, onClick = undo.onRedo)
        } else {
            ZoomControls(zoom)
        }
        PillDivider()
        if (onHistory != null && !compact) {
            PillIconButton(Lucide.History, "History (${checkpointCount ?: 0})", onClick = onHistory)
        }
        if (onShare != null) {
            PillIconButton(Lucide.Share2, "Share to chat", onClick = onShare)
        }
        Box {
            PillIconButton(Lucide.EllipsisVertical, "More", onClick = { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (compact) {
                    MenuEntry(Lucide.ZoomIn, "Zoom in (${zoom.scalePercent}%)", onClick = zoom.onZoomIn)
                    MenuEntry(Lucide.ZoomOut, "Zoom out", onClick = zoom.onZoomOut)
                    MenuEntry(Lucide.Maximize, "Fit to content") { menuOpen = false; zoom.onReset() }
                    if (onHistory != null) {
                        MenuEntry(Lucide.History, "History (${checkpointCount ?: 0})") { menuOpen = false; onHistory() }
                    }
                }
                BoardMenuEntries(menu, background) { menuOpen = false }
            }
        }
    }
}

@Composable
private fun ZoomControls(zoom: CanvasZoom) {
    PillIconButton(Lucide.ZoomOut, "Zoom out", onClick = zoom.onZoomOut)
        Text(
            text = "${zoom.scalePercent}%",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .widthIn(min = LettaDimens.Space.xxl)
                .semantics { contentDescription = "Zoom level" }
                .pointerInput(zoom.onActualSize) { detectTapGestures(onDoubleTap = { zoom.onActualSize() }) },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    PillIconButton(Lucide.ZoomIn, "Zoom in", onClick = zoom.onZoomIn)
    PillIconButton(Lucide.Maximize, "Fit to content", onClick = zoom.onReset)
}

/** The overflow's board commands and background settings, shared by both layouts. */
@Composable
private fun BoardMenuEntries(menu: CanvasMenuActions, background: CanvasBackgroundActions, close: () -> Unit) {
    MenuEntry(Lucide.Import, "Import Build Cycle") { close(); menu.onImportBuildCycle() }
    MenuEntry(Lucide.Import, "Import Daily Loop") { close(); menu.onImportDailyLoop() }
    MenuEntry(Lucide.FileJson, "Export JSON") { close(); menu.onExportJson() }
    MenuEntry(Lucide.Download, "Export SVG") { close(); menu.onExportSvg() }
    MenuEntry(Lucide.Trash2, "Clear") { close(); menu.onClear() }
    Text(
        text = "Background",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = LettaDimens.Space.lg, top = LettaDimens.Space.md, bottom = LettaDimens.Space.xs),
    )
    Row(
        modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        BoardBackgrounds.forEach { entry ->
            Box(
                modifier = Modifier
                    .size(LettaDimens.Control.iconButton)
                    .background(entry.color, CircleShape)
                    .border(
                        width = if (entry.color == background.color) LettaDimens.Space.hair else 1.dp,
                        color = if (entry.color == background.color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        shape = CircleShape,
                    )
                    .semantics { contentDescription = "Background ${entry.name}" }
                    .clickable { background.onColor(entry.color) },
            )
        }
        ColorSwatchPicker(
            current = background.color,
            palette = BoardBackgrounds,
            label = "Background color",
            onPick = background.onColor,
            swatchSize = LettaDimens.Space.xl,
            modifier = Modifier.size(LettaDimens.Control.iconButton),
        )
    }
    BackgroundPatternRows(background)
}

/** Pattern kind, spacing and tint, the way Concepts offers Grid / Dot Grid with a spacing. */
@Composable
private fun BackgroundPatternRows(background: CanvasBackgroundActions) {
    val pattern = background.pattern
    Row(
        modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CanvasBackgroundPattern.KINDS.forEach { kind ->
            MenuChip(label = kind.replaceFirstChar { it.uppercase() }, description = "Pattern $kind", selected = pattern.kind == kind) {
                background.onPattern(pattern.copy(kind = kind))
            }
        }
    }
    if (pattern.kind != CanvasBackgroundPattern.NONE) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.xs),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CanvasBackgroundPattern.SPACINGS.forEach { spacing ->
                MenuChip(label = spacing.toInt().toString(), description = "Spacing ${spacing.toInt()}", selected = pattern.spacing == spacing) {
                    background.onPattern(pattern.copy(spacing = spacing))
                }
            }
            Box(modifier = Modifier.size(LettaDimens.Space.sm))
            ColorSwatchPicker(
                current = pattern.tint(),
                palette = StrokePalette,
                label = "Pattern color",
                onPick = { background.onPattern(pattern.copy(colorHex = it.toHex())) },
                swatchSize = LettaDimens.Space.xl,
                modifier = Modifier.size(LettaDimens.Control.iconButton),
            )
        }
    }
}

@Composable
private fun MenuChip(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.semantics { contentDescription = description },
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
        )
    }
}

@Composable
private fun PillDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = LettaDimens.Space.xs)
            .size(width = 1.dp, height = LettaDimens.Space.xl)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
    )
}

/** Bottom-left: element count and the last thing that happened, small and out of the way. */
@Composable
internal fun CanvasStatusLine(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs).widthIn(max = 360.dp),
        )
    }
}

/** Board backgrounds: paper whites, warm and cool tints, and the dark boards. */
internal val BoardBackgrounds: List<NamedColor> = listOf(
    NamedColor(Color(0xFFFFFFFF), "white"),
    NamedColor(Color(0xFFF7F3EA), "paper"),
    NamedColor(Color(0xFFEFF6FF), "sky"),
    NamedColor(Color(0xFFF0FDF4), "mint"),
    NamedColor(Color(0xFF1E1E22), "charcoal"),
    NamedColor(Color(0xFF0B0F17), "midnight"),
)

/** True inside [CanvasHeaderBar]: the pills there are parts of the one bar, not bars of their own. */
internal val LocalInHeaderBar = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * The board's header: one bar across the top holding the title, the sync status and the actions,
 * rather than three islands. The pills composed inside it drop their own surfaces.
 */
@Composable
internal fun CanvasHeaderBar(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(LettaDimens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        shadowElevation = LettaDimens.Space.xs,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalInHeaderBar provides true) {
            Row(
                modifier = Modifier.padding(horizontal = LettaDimens.Space.xs, vertical = LettaDimens.Space.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
                content = content,
            )
        }
    }
}

@Composable
private fun ChromePill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (LocalInHeaderBar.current) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
            content = { content() },
        )
        return
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(LettaDimens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        shadowElevation = LettaDimens.Space.xs,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.xs, vertical = LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
            content = { content() },
        )
    }
}

@Composable
private fun PillIconButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(LettaDimens.Control.iconButtonLg).semantics { contentDescription = label }) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
    }
}

@Composable
private fun MenuEntry(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon)) },
        onClick = onClick,
    )
}

/** The title's width on a phone, leaving the row to the actions pill. */
private val COMPACT_TITLE_WIDTH = 120.dp
