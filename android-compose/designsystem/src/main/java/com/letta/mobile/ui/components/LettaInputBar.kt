package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.icons.LettaIcons

/**
 * Shared pill-shaped input bar with send button.
 *
 * Used by both the chat screen and the homepage chat field.
 *
 * @param text Current text value.
 * @param onTextChange Called when the text changes.
 * @param onSend Called with the current text when the user hits send.
 * @param placeholder Placeholder text shown when empty.
 * @param sendContentDescription Accessibility label for the send button.
 * @param enabled Whether the send button is enabled (beyond the default non-blank check).
 * @param maxLines Maximum visible lines for the text field.
 * @param canSendOverride Optional override for the send enablement check —
 *   useful when the bar has non-text content staged (e.g. image attachments)
 *   so Send is enabled with an empty text field.
 * @param leadingContent Optional slot rendered to the left of the text field,
 *   typically an attach button.
 */
@Composable
fun LettaInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (String) -> Unit,
    placeholder: String,
    sendContentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    maxLines: Int = 4,
    canSendOverride: Boolean? = null,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val canSend = (canSendOverride ?: text.isNotBlank()) && enabled

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leadingContent?.invoke()
        TextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colorScheme.onSurfaceVariant,
                )
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = colorScheme.onSurface,
            ),
            maxLines = maxLines,
            singleLine = maxLines == 1,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                unfocusedContainerColor = colorScheme.surfaceContainerHigh,
                focusedContainerColor = colorScheme.surfaceContainerHigh,
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = colorScheme.primary,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (canSend) {
                        onSend(text)
                    }
                },
            ),
        )

        FilledIconButton(
            onClick = { if (canSend) onSend(text) },
            enabled = canSend,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = colorScheme.primary,
                contentColor = colorScheme.onPrimary,
                disabledContainerColor = colorScheme.surfaceContainerHigh,
                disabledContentColor = colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            ),
        ) {
            Icon(
                LettaIcons.Send,
                contentDescription = sendContentDescription,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
