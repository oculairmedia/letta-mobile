package com.letta.mobile.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
    )
}
