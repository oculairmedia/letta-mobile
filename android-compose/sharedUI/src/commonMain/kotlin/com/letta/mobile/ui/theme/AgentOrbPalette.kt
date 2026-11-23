package com.letta.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * The agent orbs' illustrative palette, from the Penpot "App Mockups v2" desktop boards. The orb
 * gradients are identity colours, not theme roles: an agent's orb keeps them in light and dark.
 */

/** Diagonal linear-gradient pairs (start to end) for the rail and sidebar orbs, by orb index. */
internal val AgentOrbGradients: List<Pair<Color, Color>> = listOf(
    Color(0xFFF0A03C) to Color(0xFFE0457B),
    Color(0xFFE0457B) to Color(0xFF8E5CFF),
    Color(0xFF3FA0F0) to Color(0xFF3FE0C0),
    Color(0xFF7AD08F) to Color(0xFF3FA0A0),
    Color(0xFF8E7CFF) to Color(0xFF3F6EF0),
    Color(0xFF3FC0D0) to Color(0xFF3F90A0),
)

/**
 * The assistant sphere's radial, from the brand tokens: the vermilion's lit face at the centre, Buoy
 * Vermilion at the 0.55 stop, and the mark's deep petrol stone at the rim.
 */
internal val AgentSphereCore: Color = Color(LettaColorTokens.PRIMARY_HIGHLIGHT)
internal val AgentSphereMid: Color = Color(LettaColorTokens.DARK_PRIMARY)
internal val AgentSphereRim: Color = Color(LettaColorTokens.LIGHT_MARK_STONE)

/** The sphere's glossy highlight, fading to clear. */
internal val AgentSphereHighlight: Color = Color(0x80FFFFFF)
internal val AgentSphereHighlightClear: Color = Color(0x00FFFFFF)
