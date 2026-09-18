package com.letta.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import com.letta.mobile.ui.theme.LettaDimens

@Composable
fun StatusChip(
    status: String,
    modifier: Modifier = Modifier,
) {
    val normalizedStatus = status.trim().lowercase(Locale.ROOT)
    val (backgroundColor, contentColor) = when (normalizedStatus) {
        "completed" -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        "error", "failed", "cancelled", "expired" -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        "created", "running", "pending", "processing", "working", "busy" -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = status,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(RoundedCornerShape(LettaDimens.Space.xs))
            .background(backgroundColor)
            .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.hair),
    )
}
