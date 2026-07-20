package com.letta.mobile.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.scaledBy

@Composable
fun LatencyText(
    latencyMs: Float,
    modifier: Modifier = Modifier,
) {
    if (latencyMs < 0f) return

    Text(
        text = latencyMs.humanReadableDuration(),
        modifier = modifier.alpha(0.5f),
        style = MaterialTheme.typography.labelSmall.scaledBy(LocalChatFontScale.current),
    )
}

fun Float.humanReadableDuration(): String {
    return when {
        this < 1000f -> "${this.toInt()}ms"
        this < 60_000f -> String.format(java.util.Locale.US, "%.1fs", this / 1000f)
        else -> {
            val minutes = (this / 60_000f).toInt()
            val seconds = ((this % 60_000f) / 1000f).toInt()
            "${minutes}m ${seconds}s"
        }
    }
}
