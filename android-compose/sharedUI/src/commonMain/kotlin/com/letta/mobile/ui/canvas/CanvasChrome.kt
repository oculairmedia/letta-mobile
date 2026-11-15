package com.letta.mobile.ui.canvas

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextOverflow
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
import com.letta.mobile.data.canvas.CanvasSyncHealth
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.canvas_menu_share
import com.letta.mobile.ui.components.foundation.LettaButtonIcon
import com.letta.mobile.ui.components.foundation.LettaColorSwatch
import com.letta.mobile.ui.components.foundation.LettaDivider
import com.letta.mobile.ui.components.foundation.LettaIconButton
import com.letta.mobile.ui.components.foundation.LettaSwatch
import com.letta.mobile.ui.components.foundation.LettaVerticalDivider
import com.letta.mobile.ui.components.foundation.lettaPanelBorder
import com.letta.mobile.ui.host.LettaMenu
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.tokens.LettaTextStyles
import org.jetbrains.compose.resources.stringResource
import com.letta.mobile.ui.components.foundation.LettaMenuEntry
import com.letta.mobile.ui.components.foundation.LettaMenuItem
import com.letta.mobile.ui.components.foundation.LettaChipTab

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
            PillIconButton(LettaIcons.ArrowBack, "Back", onClick = onNavigateBack)
        }
        Column(modifier = Modifier.padding(start = if (onNavigateBack == null) LettaDimens.Space.md else LettaDimens.Space.hair, end = LettaDimens.Space.lg)) {
            Text(
                text = title,
                style = LettaTextStyles.titleSemibold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = if (compact) COMPACT_TITLE_WIDTH else CANVAS_TITLE_MAX_WIDTH),
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
 * What the board's overflow (more) menu lists besides the board commands: zoom and history when
 * no pill carries them ([compact]), share when no button of its own does, the sync status when no
 * badge shows it, and the host page's own entries first.
 */
internal class CanvasOverflow(
    val zoom: CanvasZoom,
    val checkpointCount: Int?,
    val onHistory: (() -> Unit)?,
    val menu: CanvasMenuActions,
    val background: CanvasBackgroundActions,
    val compact: Boolean,
    val onShare: (() -> Unit)? = null,
    val sync: CanvasSyncHealth? = null,
    val host: List<CanvasHostMenuEntry> = emptyList(),
)

/** The overflow menu itself, anchored to whichever button opened it. */
@Composable
internal fun CanvasOverflowMenu(overflow: CanvasOverflow, expanded: Boolean, onDismiss: () -> Unit) {
    LettaMenu(expanded = expanded, onDismiss = onDismiss) {
        overflow.sync?.let { CanvasSyncStatusLine(it) }
        overflow.host.forEach { entry -> LettaMenuEntry(LettaMenuItem(entry.label, entry.icon, onClick = entry.onClick), onDismiss) }
        if (overflow.sync != null || overflow.host.isNotEmpty()) LettaDivider()
        overflow.onShare?.let { share ->
            LettaMenuEntry(LettaMenuItem(stringResource(Res.string.canvas_menu_share), Lucide.Share2, onClick = share), onDismiss)
        }
        if (overflow.compact) {
            val zoom = overflow.zoom
            val zoomIn = LettaMenuItem("Zoom in (${zoom.scalePercent}%)", Lucide.ZoomIn, keepsMenuOpen = true, onClick = zoom.onZoomIn)
            LettaMenuEntry(zoomIn, onDismiss)
            LettaMenuEntry(LettaMenuItem("Zoom out", Lucide.ZoomOut, keepsMenuOpen = true, onClick = zoom.onZoomOut), onDismiss)
            LettaMenuEntry(LettaMenuItem("Fit to content", Lucide.Maximize, onClick = zoom.onReset), onDismiss)
            overflow.onHistory?.let { history ->
                LettaMenuEntry(LettaMenuItem("History (${overflow.checkpointCount ?: 0})", Lucide.History, onClick = history), onDismiss)
            }
        }
        BoardMenuEntries(overflow.menu, overflow.background, onDismiss)
    }
}

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
            CanvasOverflowMenu(
                overflow = CanvasOverflow(zoom, checkpointCount, onHistory, menu, background, compact),
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
            )
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
    LettaMenuEntry(LettaMenuItem("Import Build Cycle", Lucide.Import, onClick = menu.onImportBuildCycle), close)
    LettaMenuEntry(LettaMenuItem("Import Daily Loop", Lucide.Import, onClick = menu.onImportDailyLoop), close)
    LettaMenuEntry(LettaMenuItem("Export JSON", Lucide.FileJson, onClick = menu.onExportJson), close)
    LettaMenuEntry(LettaMenuItem("Export SVG", Lucide.Download, onClick = menu.onExportSvg), close)
    LettaMenuEntry(LettaMenuItem("Clear", Lucide.Trash2, onClick = menu.onClear), close)
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
            val swatch = LettaSwatch(entry.color, selected = entry.color == background.color, name = "Background ${entry.name}")
            LettaColorSwatch(swatch, onClick = { background.onColor(entry.color) })
        }
        ColorSwatchPicker(
            current = background.color,
            palette = BoardBackgrounds,
            label = "Background color",
            onPick = background.onColor,
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
            )
        }
    }
}

@Composable
private fun MenuChip(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    LettaChipTab(text = label, active = selected, onClick = onClick, modifier = Modifier.semantics { contentDescription = description })
}

@Composable
private fun PillDivider() {
    LettaVerticalDivider(Modifier.padding(horizontal = LettaDimens.Space.xs).height(LettaDimens.Space.xl))
}

/** Bottom-left: element count and the last thing that happened, small and out of the way. */
@Composable
internal fun CanvasStatusLine(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs)
                .widthIn(max = CANVAS_STATUS_MAX_WIDTH),
        )
    }
}

/** Board backgrounds: paper whites, warm and cool tints, and the dark boards. */
internal val BoardBackgrounds: List<NamedColor> = listOf(
    NamedColor(BoardWhite, "white"),
    NamedColor(BoardPaper, "paper"),
    NamedColor(BoardSky, "sky"),
    NamedColor(BoardMint, "mint"),
    NamedColor(BoardCharcoal, "charcoal"),
    NamedColor(BoardMidnight, "midnight"),
)

/** True inside [CanvasHeaderBar]: the pills there are parts of the one bar, not bars of their own. */
internal val LocalInHeaderBar = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * [CanvasHeaderBar]'s height: one row of [LettaDimens.Control.iconButtonLg] actions, its
 * [LettaDimens.Space.xs] padding above and below, and its hairline border. What sits under the bar
 * offsets from this rather than from a guess.
 */
internal val CanvasHeaderBarHeight: androidx.compose.ui.unit.Dp =
    LettaDimens.Control.iconButtonLg + LettaDimens.Space.xs * 2 + LettaDimens.Stroke.hairline * 2

/**
 * The board's header: one bar across the top holding the title, the sync status and the actions,
 * rather than three islands. The pills composed inside it drop their own surfaces.
 */
@Composable
internal fun CanvasHeaderBar(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        border = lettaPanelBorder(),
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
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        border = lettaPanelBorder(),
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
    LettaIconButton(LettaButtonIcon(icon, label, size = LettaDimens.Control.iconButtonLg), onClick = onClick, enabled = enabled)
}

