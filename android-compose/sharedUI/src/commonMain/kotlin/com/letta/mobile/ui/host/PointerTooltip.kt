package com.letta.mobile.ui.host

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Popup
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Tooltips and hover cards for every pointer host: desktop and web share this code. The hover
// lifecycle mirrors foundation's desktop-only TooltipArea, so desktop looks and behaves as it did.

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

/**
 * The hover lifecycle shared by plain labels and rich hover cards: the tooltip opens after [delayMillis] of
 * hovering with no button held, stays while the pointer is over the control or the tooltip, and closes on a press.
 */
@Composable
fun PointerTooltipArea(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    delayMillis: Int = TOOLTIP_SHOW_DELAY_MS,
    position: PointerTooltipPosition = PointerTooltipPosition.Cursor,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var cursor by remember { mutableStateOf(Offset.Zero) }
    var visible by remember { mutableStateOf(false) }
    val jobs = remember { arrayOfNulls<Job>(2) } // [show, hide]
    fun cancelHide() = jobs[1]?.cancel()
    fun hide() {
        jobs[0]?.cancel()
        visible = false
    }
    // Leaving the control or the tooltip schedules the hide, entering the other cancels it.
    fun scheduleHide() {
        cancelHide()
        jobs[1] = scope.launch { hide() }
    }
    fun onHover(event: PointerEvent) {
        cursor = event.changes.first().position
        cancelHide()
        val showing = visible || jobs[0]?.isActive == true
        if (showing || event.changes.any { it.pressed }) return
        jobs[0] = scope.launch {
            delay(delayMillis.toLong())
            visible = true
        }
    }
    Box(
        modifier = modifier.pointerInput(delayMillis) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    when (event.type) {
                        PointerEventType.Enter, PointerEventType.Move -> onHover(event)
                        PointerEventType.Exit -> scheduleHide()
                        PointerEventType.Press -> hide()
                    }
                }
            }
        },
    ) {
        content()
        if (visible) {
            Popup(popupPositionProvider = rememberTooltipPositionProvider(position, cursor), onDismissRequest = ::hide) {
                Box(Modifier.onHoverChange(onEnter = ::cancelHide, onExit = ::scheduleHide)) { tooltip() }
            }
        }
    }
}

/** Calls [onEnter] while the pointer enters or moves over this node and [onExit] when it leaves. */
private fun Modifier.onHoverChange(onEnter: () -> Unit, onExit: () -> Unit): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            when (awaitPointerEvent().type) {
                PointerEventType.Enter, PointerEventType.Move -> onEnter()
                PointerEventType.Exit -> onExit()
            }
        }
    }
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
        modifier = Modifier
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm)
            .widthIn(max = LettaDimens.Pane.sidePanelWidth),
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
