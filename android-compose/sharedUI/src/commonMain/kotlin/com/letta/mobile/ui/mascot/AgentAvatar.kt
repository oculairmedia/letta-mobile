package com.letta.mobile.ui.mascot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The one way an agent is pictured anywhere in the app: its mascot when it has one (moving while
 * the agent works, a still otherwise - [mascotAtWork]; [live] false forces a still), otherwise
 * the same initial-letter tile every screen used to draw for itself. Call this from a list row, a header, a chip or a picker and
 * they all agree - the shape, the fallback and the size rule live here, not at each site.
 */
@Composable
fun AgentAvatar(
    agentId: String?,
    name: String,
    size: Dp,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = size / 2,
    live: Boolean = mascotAtWork(agentId),
    onClick: (() -> Unit)? = null,
) {
    MascotAvatar(
        agentId = agentId,
        size = size,
        modifier = modifier,
        cornerRadius = cornerRadius,
        onClick = onClick,
        live = live,
        fallback = { AgentInitialTile(name, size, cornerRadius, modifier, onClick) },
    )
}

/** The letter tile an agent without a mascot gets: its initial on the secondary container. */
@Composable
fun AgentInitialTile(
    name: String,
    size: Dp,
    cornerRadius: Dp = size / 2,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = if (size >= LARGE_INITIAL_FROM) MaterialTheme.typography.titleLarge else MaterialTheme.typography.labelLarge,
        )
    }
}

private val LARGE_INITIAL_FROM = LettaDimens.Orb.lg
