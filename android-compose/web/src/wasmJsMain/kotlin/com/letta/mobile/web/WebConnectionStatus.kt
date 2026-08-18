package com.letta.mobile.web

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.web.data.WebConnectionState

internal val WebConnectionState.label: String
    get() = when (this) {
        WebConnectionState.Unconfigured -> "Not configured"
        WebConnectionState.Connecting -> "Connecting"
        is WebConnectionState.Connected -> "Connected via $transport"
        is WebConnectionState.Failed -> "Connection failed"
    }

@Composable
internal fun WebConnectionStatus(
    state: WebConnectionState,
    modifier: Modifier = Modifier,
) {
    val color: Color = when (state) {
        WebConnectionState.Unconfigured -> MaterialTheme.customColors.offlineColor
        WebConnectionState.Connecting -> MaterialTheme.customColors.reconnectingColor
        is WebConnectionState.Connected -> MaterialTheme.customColors.onlineColor
        is WebConnectionState.Failed -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            text = state.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
