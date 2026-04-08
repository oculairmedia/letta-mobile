package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (text.isBlank()) return

    Markdown(
        content = text,
        modifier = modifier.fillMaxWidth(),
        colors = markdownColor(
            text = textColor,
            codeText = MaterialTheme.colorScheme.onSurfaceVariant,
            codeBackground = MaterialTheme.colorScheme.surfaceVariant,
            dividerColor = MaterialTheme.colorScheme.outlineVariant,
            linkText = MaterialTheme.colorScheme.primary,
        ),
        typography = markdownTypography(
            text = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            code = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            h1 = MaterialTheme.typography.titleLarge.copy(color = textColor),
            h2 = MaterialTheme.typography.titleMedium.copy(color = textColor),
            h3 = MaterialTheme.typography.titleSmall.copy(color = textColor),
            h4 = MaterialTheme.typography.bodyLarge.copy(color = textColor),
            h5 = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            h6 = MaterialTheme.typography.bodySmall.copy(color = textColor),
            quote = MaterialTheme.typography.bodyMedium.copy(color = textColor.copy(alpha = 0.7f)),
            bullet = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            list = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            ordered = MaterialTheme.typography.bodyMedium.copy(color = textColor),
        ),
    )
}
