package com.letta.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class ConnectionState { Online, Offline, Reconnecting }

@Composable
fun ConnectionStatusBanner(
    state: ConnectionState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state != ConnectionState.Online,
        modifier = modifier,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        val bgColor = when (state) {
            ConnectionState.Offline -> MaterialTheme.colorScheme.error
            ConnectionState.Reconnecting -> Color(0xFFF9A825)
            ConnectionState.Online -> Color(0xFF2E7D32)
        }
        val textColor = when (state) {
            ConnectionState.Offline -> MaterialTheme.colorScheme.onError
            ConnectionState.Reconnecting -> Color(0xFF1A1A1A)
            ConnectionState.Online -> Color.White
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state == ConnectionState.Reconnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = textColor,
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = when (state) {
                    ConnectionState.Offline -> "No internet connection"
                    ConnectionState.Reconnecting -> "Reconnecting\u2026"
                    ConnectionState.Online -> "Connected"
                },
                style = MaterialTheme.typography.labelMedium,
                color = textColor,
            )
        }
    }
}
