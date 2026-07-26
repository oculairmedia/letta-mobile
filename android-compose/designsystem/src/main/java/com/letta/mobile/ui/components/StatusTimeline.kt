package com.letta.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.motion.ChatMotionPolicy
import com.letta.mobile.ui.motion.rememberChatMotionPolicy
import com.letta.mobile.ui.theme.LettaTheme
import com.letta.mobile.ui.theme.sectionTitle

/**
 * Standard sizing and layout tokens for timeline components.
 */
object TimelineDefaults {
    /** Fixed width allocated for the left timeline rail column. */
    val RailWidth: Dp = 32.dp

    /** Fixed thickness of the vertical timeline connector rail line. */
    val ConnectorWidth: Dp = 2.dp

    /** Visual diameter of a standard timeline node. */
    val NodeSize: Dp = 24.dp

    /** Size of the inner icon inside a timeline node. */
    val NodeIconSize: Dp = 14.dp

    /** Minimum touch target size required for accessibility (48dp). */
    val MinTouchTargetSize: Dp = 48.dp

    /** Minimum height of a timeline row header to guarantee a 48dp touch target. */
    val HeaderMinHeight: Dp = 48.dp
}

/**
 * Decorative vertical connector line connecting timeline nodes.
 *
 * Excluded from the accessibility tree via `clearAndSetSemantics` as it conveys
 * purely visual rail structure. Layout geometry is 100% deterministic with zero
 * measurement callbacks (`onGloballyPositioned`).
 */
@Composable
fun TimelineConnector(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.outlineVariant,
    width: Dp = TimelineDefaults.ConnectorWidth,
    isDashed: Boolean = false,
) {
    Canvas(
        modifier = modifier
            .width(width)
            .fillMaxHeight()
            .clearAndSetSemantics { },
    ) {
        val pathEffect = if (isDashed) {
            PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
        } else {
            null
        }

        drawLine(
            color = color,
            start = Offset(x = size.width / 2f, y = 0f),
            end = Offset(x = size.width / 2f, y = size.height),
            strokeWidth = width.toPx(),
            pathEffect = pathEffect,
        )
    }
}

/**
 * Visual node indicator representing a step in an assistant work timeline.
 *
 * When [onClick] is provided, an interactive 48dp minimum touch target is enforced
 * via `minimumInteractiveComponentSize()`. When non-interactive, decorative node visuals
 * are excluded from semantics unless [contentDescription] is explicitly supplied.
 */
@Composable
fun TimelineNode(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    borderColor: Color? = null,
    size: Dp = TimelineDefaults.NodeSize,
    iconSize: Dp = TimelineDefaults.NodeIconSize,
    icon: ImageVector? = null,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: (@Composable () -> Unit)? = null,
) {
    val baseModifier = if (onClick != null) {
        // The inner Icon is decorative (contentDescription = null), so an interactive
        // node carries no label unless we apply it here. onClickLabel only names the
        // ACTION ("Confirm step"), it does not describe the element.
        modifier
            // defaultMinSize, not only minimumInteractiveComponentSize(): the latter
            // reads an ambient CompositionLocal and silently becomes a no-op outside a
            // Material theme, so the 48dp guarantee these primitives are supposed to own
            // would depend on the caller's environment.
            .minimumInteractiveComponentSize()
            .defaultMinSize(
                minWidth = TimelineDefaults.MinTouchTargetSize,
                minHeight = TimelineDefaults.MinTouchTargetSize,
            )
            .clickable(onClickLabel = onClickLabel, onClick = onClick)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
    } else if (contentDescription != null) {
        modifier.semantics { this.contentDescription = contentDescription }
    } else {
        modifier.clearAndSetSemantics { }
    }

    Box(
        modifier = baseModifier,
        contentAlignment = Alignment.Center,
    ) {
        val circleModifier = Modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (borderColor != null) {
                    Modifier.border(1.dp, borderColor, CircleShape)
                } else {
                    Modifier
                },
            )

        Surface(
            modifier = circleModifier,
            color = containerColor,
            contentColor = contentColor,
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (content != null) {
                    content()
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize),
                        tint = contentColor,
                    )
                }
            }
        }
    }
}

/**
 * Accessible status row with expand/collapse details and integrated motion policy.
 *
 * Semantics expose state description (e.g., "Completed, Expanded") and native `expand`/`collapse`
 * accessibility actions. Touch target is guaranteed at >= 48dp.
 */
@Composable
fun CollapsibleStatusRow(
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    statusLabel: String? = null,
    statusColor: Color? = MaterialTheme.colorScheme.onSurfaceVariant,
    node: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    motionPolicy: ChatMotionPolicy = rememberChatMotionPolicy(),
    content: (@Composable () -> Unit)? = null,
) {
    CollapsibleStatusRow(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.sectionTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        modifier = modifier,
        subtitle = subtitle,
        statusLabel = statusLabel,
        statusColor = statusColor,
        node = node,
        badge = badge,
        motionPolicy = motionPolicy,
        content = content,
    )
}

/**
 * Overload for [CollapsibleStatusRow] supporting a custom title composable.
 */
@Composable
fun CollapsibleStatusRow(
    title: @Composable () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    statusLabel: String? = null,
    statusColor: Color? = MaterialTheme.colorScheme.onSurfaceVariant,
    node: (@Composable () -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    motionPolicy: ChatMotionPolicy = rememberChatMotionPolicy(),
    content: (@Composable () -> Unit)? = null,
) {
    val stateDesc = remember(expanded, statusLabel) {
        buildString {
            statusLabel?.let { append("$it, ") }
            append(if (expanded) "Expanded" else "Collapsed")
        }
    }

    val toggleActionLabel = if (expanded) "Collapse details" else "Expand details"

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = if (motionPolicy.isReducedMotionEnabled) snap() else MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "status_row_chevron",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = motionPolicy.expansion.sizeSpec),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = TimelineDefaults.HeaderMinHeight)
                .clickable(
                    onClickLabel = toggleActionLabel,
                    onClick = { onExpandedChange(!expanded) },
                )
                .semantics(mergeDescendants = true) {
                    stateDescription = stateDesc
                    if (expanded) {
                        collapse {
                            onExpandedChange(false)
                            true
                        }
                    } else {
                        expand {
                            onExpandedChange(true)
                            true
                        }
                    }
                }
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (node != null) {
                Box(
                    modifier = Modifier.padding(end = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    node()
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        title()
                    }
                    if (statusLabel != null) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }

                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (badge != null) {
                Spacer(modifier = Modifier.width(8.dp))
                badge()
            }

            if (content != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = LettaIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (content != null) {
            AnimatedVisibility(
                visible = expanded,
                enter = motionPolicy.expansion.enter,
                exit = motionPolicy.expansion.exit,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = if (node != null) 32.dp else 0.dp, top = 4.dp, bottom = 8.dp),
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Individual timeline entry wrapping a deterministic left rail and right content area.
 *
 * Continuous vertical connectors are rendered using pure layout bounds without `onGloballyPositioned`.
 */
@Composable
fun StatusTimelineItem(
    modifier: Modifier = Modifier,
    node: (@Composable () -> Unit)? = null,
    showTopConnector: Boolean = true,
    showBottomConnector: Boolean = true,
    connectorColor: Color = MaterialTheme.colorScheme.outlineVariant,
    connectorWidth: Dp = TimelineDefaults.ConnectorWidth,
    isConnectorDashed: Boolean = false,
    railWidth: Dp = TimelineDefaults.RailWidth,
    nodeHeaderHeight: Dp = TimelineDefaults.HeaderMinHeight,
    content: @Composable () -> Unit,
) {
    Row(
        // height(IntrinsicSize.Min) makes the row size to its CONTENT, so the rail's
        // fillMaxHeight() below resolves to the content height. Without it the rail
        // fills the incoming (screen) constraint instead, making every item as tall as
        // the viewport and pushing later rows out of view.
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(railWidth)
                .fillMaxHeight()
                .clearAndSetSemantics { },
            contentAlignment = Alignment.TopCenter,
        ) {
            // Draw continuous connector line segments
            Canvas(
                modifier = Modifier
                    .width(connectorWidth)
                    .fillMaxHeight(),
            ) {
                val pathEffect = if (isConnectorDashed) {
                    PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                } else {
                    null
                }

                val anchorY = nodeHeaderHeight.toPx() / 2f

                if (showTopConnector) {
                    drawLine(
                        color = connectorColor,
                        start = Offset(x = size.width / 2f, y = 0f),
                        end = Offset(x = size.width / 2f, y = anchorY),
                        strokeWidth = connectorWidth.toPx(),
                        pathEffect = pathEffect,
                    )
                }

                if (showBottomConnector) {
                    drawLine(
                        color = connectorColor,
                        start = Offset(x = size.width / 2f, y = anchorY),
                        end = Offset(x = size.width / 2f, y = size.height),
                        strokeWidth = connectorWidth.toPx(),
                        pathEffect = pathEffect,
                    )
                }
            }

            if (node != null) {
                Box(
                    modifier = Modifier
                        .height(nodeHeaderHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    node()
                }
            }
        }

        Box(
            modifier = Modifier.weight(1f),
        ) {
            content()
        }
    }
}

/**
 * Scope for constructing timeline items inside [StatusTimeline].
 */
interface StatusTimelineScope {
    fun item(
        key: Any? = null,
        content: @Composable (isFirst: Boolean, isLast: Boolean) -> Unit,
    )
}

private class StatusTimelineScopeImpl : StatusTimelineScope {
    val items = mutableListOf<Pair<Any?, @Composable (isFirst: Boolean, isLast: Boolean) -> Unit>>()

    override fun item(
        key: Any?,
        content: @Composable (isFirst: Boolean, isLast: Boolean) -> Unit,
    ) {
        items.add(key to content)
    }
}

/**
 * Domain-neutral vertical container for structured timeline items.
 */
@Composable
fun <T> StatusTimeline(
    items: List<T>,
    modifier: Modifier = Modifier,
    key: ((T) -> Any)? = null,
    itemContent: @Composable (item: T, isFirst: Boolean, isLast: Boolean) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            val isFirst = index == 0
            val isLast = index == items.lastIndex
            val itemKey = key?.invoke(item) ?: index

            key(itemKey) {
                itemContent(item, isFirst, isLast)
            }
        }
    }
}

/**
 * Slot-based DSL overload for [StatusTimeline].
 */
@Composable
fun StatusTimeline(
    modifier: Modifier = Modifier,
    content: StatusTimelineScope.() -> Unit,
) {
    val scopeImpl = remember(content) { StatusTimelineScopeImpl().apply(content) }
    Column(modifier = modifier.fillMaxWidth()) {
        scopeImpl.items.forEachIndexed { index, (itemKey, itemComposable) ->
            val isFirst = index == 0
            val isLast = index == scopeImpl.items.lastIndex

            key(itemKey ?: index) {
                itemComposable(isFirst, isLast)
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Previews
// -----------------------------------------------------------------------------

@PreviewLightDark
@Composable
internal fun PreviewStatusTimelineContent() {
    LettaTheme(dynamicColor = false) {
        Surface {
            Column(modifier = Modifier.padding(16.dp)) {
                StatusTimeline(
                    items = listOf(
                        Triple("Analyzing codebase", "Completed", false),
                        Triple("Executing gradle test", "Running", true),
                        Triple("Generating response", "Pending", false),
                    ),
                ) { (title, status, expanded), isFirst, isLast ->
                    StatusTimelineItem(
                        node = {
                            TimelineNode(
                                icon = if (status == "Completed") LettaIcons.Check else LettaIcons.Tool,
                                containerColor = if (status == "Running") {
                                    MaterialTheme.colorScheme.tertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                },
                                contentColor = if (status == "Running") {
                                    MaterialTheme.colorScheme.onTertiaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                },
                            )
                        },
                        showTopConnector = !isFirst,
                        showBottomConnector = !isLast,
                    ) {
                        CollapsibleStatusRow(
                            title = title,
                            statusLabel = status,
                            expanded = expanded,
                            onExpandedChange = {},
                        ) {
                            Text(
                                text = "Detailed step execution logs and parameters display here.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun StatusTimelinePreview() = PreviewStatusTimelineContent()
