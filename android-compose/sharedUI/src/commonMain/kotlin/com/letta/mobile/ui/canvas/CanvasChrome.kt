package com.letta.mobile.ui.canvas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.ZoomIn
import com.composables.icons.lucide.ZoomOut

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
) {
    ChromePill(modifier = modifier) {
        if (onNavigateBack != null) {
            PillIconButton(Lucide.ChevronLeft, "Back", onClick = onNavigateBack)
        }
        Column(modifier = Modifier.padding(start = if (onNavigateBack == null) 12.dp else 2.dp, end = 14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
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

/** Top-right: history and share as icons, everything else behind the overflow. */
@Composable
internal fun CanvasActionsPill(
    checkpointCount: Int?,
    onHistory: (() -> Unit)?,
    onShare: (() -> Unit)?,
    menu: CanvasMenuActions,
    background: Color,
    onBackground: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ChromePill(modifier = modifier) {
        if (onHistory != null) {
            PillIconButton(Lucide.History, "History (${checkpointCount ?: 0})", onClick = onHistory)
        }
        if (onShare != null) {
            PillIconButton(Lucide.Share2, "Share to chat", onClick = onShare)
        }
        Box {
            PillIconButton(Lucide.EllipsisVertical, "More", onClick = { menuOpen = true })
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                MenuEntry(Lucide.Import, "Import Build Cycle") { menuOpen = false; menu.onImportBuildCycle() }
                MenuEntry(Lucide.Import, "Import Daily Loop") { menuOpen = false; menu.onImportDailyLoop() }
                MenuEntry(Lucide.FileJson, "Export JSON") { menuOpen = false; menu.onExportJson() }
                MenuEntry(Lucide.Download, "Export SVG") { menuOpen = false; menu.onExportSvg() }
                MenuEntry(Lucide.Trash2, "Clear") { menuOpen = false; menu.onClear() }
                Text(
                    text = "Background",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp),
                )
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    BoardBackgrounds.forEach { entry ->
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .background(entry.color, CircleShape)
                                .border(
                                    width = if (entry.color == background) 2.dp else 1.dp,
                                    color = if (entry.color == background) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .semantics { contentDescription = "Background ${entry.name}" }
                                .clickable { onBackground(entry.color) },
                        )
                    }
                }
            }
        }
    }
}

/** Bottom-right: zoom out, the current scale, zoom in, and fit. */
@Composable
internal fun CanvasZoomPill(
    scalePercent: Int,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ChromePill(modifier = modifier) {
        PillIconButton(Lucide.ZoomOut, "Zoom out", onClick = onZoomOut)
        Text(
            text = "$scalePercent%",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.widthIn(min = 40.dp).semantics { contentDescription = "Zoom level" },
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        PillIconButton(Lucide.ZoomIn, "Zoom in", onClick = onZoomIn)
        PillIconButton(Lucide.Maximize, "Reset view", onClick = onReset)
    }
}

/** Bottom-left: element count and the last thing that happened, small and out of the way. */
@Composable
internal fun CanvasStatusLine(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.85f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp).widthIn(max = 360.dp),
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

@Composable
private fun ChromePill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            content = { content() },
        )
    }
}

@Composable
private fun PillIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp).semantics { contentDescription = label }) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun MenuEntry(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp)) },
        onClick = onClick,
    )
}
