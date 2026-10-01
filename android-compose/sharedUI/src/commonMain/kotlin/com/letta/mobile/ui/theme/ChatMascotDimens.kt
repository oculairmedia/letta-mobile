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

    /** The fresh conversation's greeting: the agent at hero size above the starter prompts. */
    val welcomeHero: Dp = 220.dp

    /** The stand-in sphere a welcome shows for an agent without a mascot. */
    val welcomeFallbackSphere: Dp = 96.dp
}
