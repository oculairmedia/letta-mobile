package com.letta.mobile.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import org.jetbrains.jewel.foundation.theme.LocalContentColor as JewelLocalContentColor
import org.jetbrains.jewel.ui.component.Chip as JewelChip
import org.jetbrains.jewel.ui.component.DefaultButton as JewelDefaultButton
import org.jetbrains.jewel.ui.component.Icon as JewelIcon
import org.jetbrains.jewel.ui.component.IconButton as JewelIconButton
import org.jetbrains.jewel.ui.component.OutlinedButton as JewelOutlinedButton
import org.jetbrains.jewel.ui.component.RadioButtonChip as JewelRadioButtonChip
import org.jetbrains.jewel.ui.component.TextArea as JewelTextArea
import org.jetbrains.jewel.ui.component.TextField as JewelTextField
import org.jetbrains.jewel.ui.component.Text as JewelText
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.ui.theme.LettaDimens

// Snappy: 450ms read as "the tooltip is broken" on quick hovers.
private const val TooltipShowDelayMs = 150

/** Simple text tooltip backed by the shared desktop hover lifecycle. */
@Composable
internal fun DesktopTooltip(
    text: String,
    modifier: Modifier = Modifier,
    delayMillis: Int = TooltipShowDelayMs,
    position: DesktopTooltipPosition = DesktopTooltipPosition.Cursor,
    content: @Composable () -> Unit,
) {
    DesktopTooltipArea(
        modifier = modifier,
        delayMillis = delayMillis,
        position = position,
        tooltip = { DesktopTooltipSurface(text) },
    ) {
        content()
    }
}

internal enum class DesktopTooltipPosition {
    Cursor,
    BelowAnchor,
}

/** Shared hover lifecycle for simple labels and rich desktop hover cards. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DesktopTooltipArea(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    delayMillis: Int = TooltipShowDelayMs,
    position: DesktopTooltipPosition = DesktopTooltipPosition.Cursor,
    content: @Composable () -> Unit,
) {
    val placement = when (position) {
        DesktopTooltipPosition.Cursor -> TooltipPlacement.CursorPoint(offset = DpOffset(LettaDimens.Space.md, LettaDimens.Space.md))
        DesktopTooltipPosition.BelowAnchor -> TooltipPlacement.ComponentRect(
            anchor = Alignment.BottomCenter,
            alignment = Alignment.TopCenter,
            offset = DpOffset(0.dp, LettaDimens.Space.sm),
        )
    }
    TooltipArea(
        tooltip = tooltip,
        modifier = modifier,
        delayMillis = delayMillis,
        tooltipPlacement = placement,
        content = content,
    )
}

/**
 * The same tooltip with a time label beside the title and a short body under it (the rail's
 * agent hover: name, last activity, last message). One primitive, two densities.
 */
@Composable
internal fun DesktopRichTooltip(
    title: String,
    modifier: Modifier = Modifier,
    timeLabel: String? = null,
    body: String? = null,
    delayMillis: Int = TooltipShowDelayMs,
    position: DesktopTooltipPosition = DesktopTooltipPosition.Cursor,
    content: @Composable () -> Unit,
) {
    DesktopTooltipArea(
        modifier = modifier,
        delayMillis = delayMillis,
        position = position,
        tooltip = { DesktopTooltipSurface(title, timeLabel = timeLabel, body = body) },
    ) {
        content()
    }
}

@Composable
private fun DesktopTooltipSurface(text: String, timeLabel: String? = null, body: String? = null) {
    val rich = timeLabel != null || !body.isNullOrBlank()
    Surface(
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = LettaDimens.Space.sm,
    ) {
        if (!rich) {
            Text(
                text = text,
                modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
                style = MaterialTheme.typography.labelMedium,
            )
        } else {
            Column(
                modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm).widthIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (timeLabel != null) {
                        Text(
                            text = timeLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
    }
}

@Composable
internal fun DesktopDefaultButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    JewelDefaultButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        content = content,
    )
}

@Composable
internal fun DesktopOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    JewelOutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        content = content,
    )
}

@Composable
internal fun DesktopRadioChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    JewelRadioButtonChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        content = content,
    )
}

@Composable
internal fun DesktopSelectableChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    JewelChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        content = content,
    )
}

@Composable
internal fun DesktopIconButton(
    imageVector: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconModifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val button: @Composable () -> Unit = {
        JewelIconButton(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
        ) {
            JewelIcon(
                imageVector = imageVector,
                contentDescription = contentDescription,
                modifier = iconModifier,
                tint = tint,
            )
        }
    }

    if (contentDescription.isNullOrBlank()) {
        button()
    } else {
        DesktopTooltip(text = contentDescription) {
            button()
        }
    }
}

@Composable
internal fun DesktopButtonContent(
    text: String,
    icon: ImageVector? = null,
) {
    val contentColor = JewelLocalContentColor.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            JewelIcon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
            )
        }
        JewelText(text = text, color = contentColor)
    }
}

@Composable
internal fun DesktopControlText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    JewelText(
        text = text,
        modifier = modifier,
        color = JewelLocalContentColor.current,
        maxLines = maxLines,
        overflow = overflow,
    )
}

@Composable
internal fun DesktopTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(value)
        }
    }

    JewelTextField(
        value = fieldValue,
        onValueChange = { nextValue ->
            fieldValue = nextValue
            if (nextValue.text != value) {
                onValueChange(nextValue.text)
            }
        },
        enabled = enabled,
        placeholder = placeholder?.let { text ->
            { DesktopControlText(text) }
        },
        visualTransformation = visualTransformation,
        modifier = modifier,
    )
}

@Composable
internal fun DesktopTextArea(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    placeholder: String? = null,
    maxLines: Int = Int.MAX_VALUE,
    undecorated: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions(),
    // Pads the inner text content away from the field border (e.g. the memory
    // block value area, which otherwise crowds its edges).
    decorationBoxModifier: Modifier = Modifier,
) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(value)
        }
    }

    JewelTextArea(
        value = fieldValue,
        onValueChange = { nextValue ->
            fieldValue = nextValue
            if (nextValue.text != value) {
                onValueChange(nextValue.text)
            }
        },
        enabled = enabled,
        placeholder = placeholder?.let { text ->
            { DesktopControlText(text) }
        },
        maxLines = maxLines,
        undecorated = undecorated,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        modifier = modifier,
        decorationBoxModifier = decorationBoxModifier,
    )
}
