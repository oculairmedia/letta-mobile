package com.letta.mobile.ui.host

import androidx.compose.runtime.Composable

// letta-mobile-8k3xs: every pointer host's hover pieces (desktop and web), built on the shared PointerTooltip.

/** A pointer host's hover label. */
internal val pointerTooltip: @Composable (String, @Composable () -> Unit) -> Unit = { text, content -> PointerTooltip(text) { content() } }

/** A pointer host's hover card; [timeLabel] formats the card's raw timestamp. */
internal fun pointerHoverCard(timeLabel: (String) -> String): @Composable (LettaHoverCard, @Composable () -> Unit) -> Unit =
    { card, content ->
        PointerTooltipArea(tooltip = { PointerTooltipSurface(card.title, card.timestamp?.let(timeLabel), card.body) }) { content() }
    }
