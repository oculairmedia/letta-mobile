package com.letta.mobile.ui.host

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.DialogWindowScope
import androidx.compose.ui.window.rememberDialogState
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The desktop pointer host (letta-mobile-ckmub.3.1): the shared pointer containers plus what only a
 * desktop window can do: cursor tooltips and hover cards, right-click row menus, and confirmations
 * that pop out as their own undecorated window. [timeLabel] formats a hover card's raw timestamp.
 */
fun LettaHost.Companion.desktopPointer(timeLabel: (String) -> String): LettaHost = Pointer.copy(
    alert = { spec, content -> AlertWindow(spec, content) },
    tooltip = { text, content -> PointerTooltip(text) { content() } },
    hoverCard = { card, content ->
        PointerTooltipArea(tooltip = { PointerTooltipSurface(card.title, card.timestamp?.let(timeLabel), card.body) }) { content() }
    },
    rowMenu = { items, content ->
        ContextMenuArea(items = { items.filter { it.enabled }.map { ContextMenuItem(it.label, it.onClick) } }) { content() }
    },
)

/** Where a pointer tooltip opens: at the cursor, or centred under its control. */
enum class PointerTooltipPosition { Cursor, BelowAnchor }

// Snappy: 450ms read as "the tooltip is broken" on quick hovers.
private const val TOOLTIP_SHOW_DELAY_MS = 150

/** A text tooltip on hover. */
@Composable
fun PointerTooltip(
    text: String,
    modifier: Modifier = Modifier,
    delayMillis: Int = TOOLTIP_SHOW_DELAY_MS,
    position: PointerTooltipPosition = PointerTooltipPosition.Cursor,
    content: @Composable () -> Unit,
) {
    PointerTooltipArea(tooltip = { PointerTooltipSurface(text) }, modifier, delayMillis, position, content)
}

/** The hover lifecycle shared by plain labels and rich hover cards. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PointerTooltipArea(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    delayMillis: Int = TOOLTIP_SHOW_DELAY_MS,
    position: PointerTooltipPosition = PointerTooltipPosition.Cursor,
    content: @Composable () -> Unit,
) {
    val placement = when (position) {
        PointerTooltipPosition.Cursor -> TooltipPlacement.CursorPoint(offset = DpOffset(LettaDimens.Space.md, LettaDimens.Space.md))
        PointerTooltipPosition.BelowAnchor -> TooltipPlacement.ComponentRect(
            anchor = Alignment.BottomCenter,
            alignment = Alignment.TopCenter,
            offset = DpOffset(0.dp, LettaDimens.Space.sm),
        )
    }
    TooltipArea(tooltip = tooltip, modifier = modifier, delayMillis = delayMillis, tooltipPlacement = placement, content = content)
}

/** A tooltip's card: one label, or a title with a time label beside it and a short body under it. */
@Composable
fun PointerTooltipSurface(text: String, timeLabel: String? = null, body: String? = null) {
    Surface(
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = LettaDimens.Space.sm,
    ) {
        if (timeLabel == null && body.isNullOrBlank()) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            RichTooltipBody(text, timeLabel, body)
        }
    }
}

@Composable
private fun RichTooltipBody(title: String, timeLabel: String?, body: String?) {
    Column(
        modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm).widthIn(max = LettaDimens.Pane.sidePanelWidth),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            timeLabel?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (!body.isNullOrBlank()) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * An alert as a real, separate desktop window: it pops out of the app rather than dimming the page.
 * Undecorated, with its own draggable title bar and close button; sized to its content.
 */
@Composable
private fun AlertWindow(spec: PaneSpec, content: @Composable ColumnScope.() -> Unit) {
    val width = if (spec.size.width == Dp.Unspecified) LettaDimens.Pane.alertWidth else spec.size.width
    val state = rememberDialogState(size = DpSize(width, Dp.Unspecified))
    DialogWindow(onCloseRequest = spec.onDismiss, state = state, title = spec.title.orEmpty(), undecorated = true, resizable = false) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
            modifier = spec.modifier.testTag(LettaPaneTags.ALERT),
        ) {
            Column {
                AlertTitleBar(this@DialogWindow, spec.title.orEmpty(), spec.onDismiss)
                Box(Modifier.fillMaxWidth().height(LettaDimens.Stroke.hairline).background(MaterialTheme.colorScheme.outlineVariant))
                Column(Modifier.padding(top = LettaDimens.Space.lg).then(rememberPaneFocus(spec.onDismiss)).then(spec.testTag?.let { Modifier.testTag(it) } ?: Modifier)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun AlertTitleBar(window: DialogWindowScope, title: String, onDismiss: () -> Unit) {
    with(window) {
        WindowDraggableArea(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LettaDimens.Control.actionButton)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = LettaDimens.Space.lg),
                )
                Box(
                    modifier = Modifier
                        .size(width = LettaDimens.Orb.railSlotWidth, height = LettaDimens.Control.actionButton)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = LettaIcons.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(LettaDimens.Control.icon),
                    )
                }
            }
        }
    }
}
