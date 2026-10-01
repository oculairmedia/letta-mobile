package com.letta.mobile.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * letta-mobile-bglj6.1: where the agent's mascot stands on the shared chat page, at the sizes
 * the desktop chat drew it. They are the character's seats, not spacing choices.
 */
object ChatMascotDimens {
    /** The composer companion's live size beside the prompt; the body spans ~60 % of it. */
    val composerCompanion: Dp = 120.dp

    /** Width the prompt row reserves for the companion at the box's left edge. */
    val composerCompanionSlot: Dp = 108.dp

    /**
     * The open docked panel's avatar badge: a disc at the top centre of the panel, straddling its
     * top edge, with the companion inside instead of beside the bar (the bar keeps the panel's
     * whole width).
     */
    val dockBadge: Dp = 76.dp

    /** The companion's seat inside [dockBadge]: the body (~60 % of it) sits within the disc. */
    val dockBadgeSeat: Dp = 100.dp

    /** The fresh conversation's greeting: the agent at hero size above the starter prompts. */
    val welcomeHero: Dp = 220.dp

    /** The stand-in sphere a welcome shows for an agent without a mascot. */
    val welcomeFallbackSphere: Dp = 96.dp

    /** The stand-in sphere the collapsed dock shows for an agent without a mascot. */
    val collapsedFallbackSphere: Dp = 72.dp
}
