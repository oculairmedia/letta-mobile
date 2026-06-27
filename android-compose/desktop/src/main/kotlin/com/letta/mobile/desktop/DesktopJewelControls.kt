package com.letta.mobile.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
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

private const val TooltipShowDelayMs = 450L
private const val TooltipGapPx = 6

/**
 * A tooltip that sits BESIDE the hovered element with a small pointer arrow, and
 * intelligently picks the side: elements in the left half of the window get the
 * tooltip on their right, elements in the right half get it on their left — so
 * the label never covers what you're pointing at and never runs off the window
 * edge. Built on a [Popup] with an explicit position provider, because the stock
 * TooltipArea can neither draw an arrow nor flip sides.
 */
@Composable
internal fun DesktopTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var show by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(IntRect.Zero) }
    var rootWidth by remember { mutableStateOf(0) }

    LaunchedEffect(hovered) {
        if (hovered) {
            delay(TooltipShowDelayMs)
            show = true
        } else {
            show = false
        }
    }

    Box(
        modifier = modifier
            .hoverable(interaction)
            .onGloballyPositioned { coords ->
                val b = coords.boundsInWindow()
                anchor = IntRect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
                rootWidth = coords.findRootCoordinates().size.width
            },
    ) {
        content()
        if (show && rootWidth > 0) {
            val placeRight = anchor.center.x < rootWidth / 2
            Popup(
                popupPositionProvider = remember(anchor, placeRight) {
                    SideTooltipPositionProvider(anchor = anchor, placeRight = placeRight)
                },
                properties = PopupProperties(focusable = false),
            ) {
                TooltipChip(text = text, arrowOnLeft = placeRight)
            }
        }
    }
}

/** Places the popup beside [anchor] (window coords): right when [placeRight], else left. */
private class SideTooltipPositionProvider(
    private val anchor: IntRect,
    private val placeRight: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val y = (anchor.top + anchor.height / 2 - popupContentSize.height / 2)
            .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        val x = (if (placeRight) anchor.right + TooltipGapPx else anchor.left - popupContentSize.width - TooltipGapPx)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

@Composable
private fun TooltipChip(text: String, arrowOnLeft: Boolean) {
    val bg = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (arrowOnLeft) TooltipArrow(pointingLeft = true, color = bg)
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = bg,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 6.dp,
        ) {
            DesktopControlText(text = text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp))
        }
        if (!arrowOnLeft) TooltipArrow(pointingLeft = false, color = bg)
    }
}

@Composable
private fun TooltipArrow(pointingLeft: Boolean, color: Color) {
    Canvas(modifier = Modifier.size(width = 5.dp, height = 11.dp)) {
        val path = Path().apply {
            if (pointingLeft) {
                moveTo(size.width, 0f)
                lineTo(0f, size.height / 2f)
                lineTo(size.width, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
            }
            close()
        }
        drawPath(path, color)
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
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
