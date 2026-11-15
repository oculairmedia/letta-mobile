package com.letta.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * The agent orbs' illustrative palette, from the Penpot "App Mockups v2" desktop boards. These are
 * artwork colours, not theme roles: an agent's orb keeps its identity colours in light and dark.
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

/** The assistant sphere's radial teal: centre, the 0.55 stop, and rim. */
internal val AgentSphereCore: Color = Color(0xFF7CF0DE)
internal val AgentSphereMid: Color = Color(0xFF00BFA5)
internal val AgentSphereRim: Color = Color(0xFF00897B)

/** The sphere's glossy highlight, fading to clear. */
internal val AgentSphereHighlight: Color = Color(0x80FFFFFF)
internal val AgentSphereHighlightClear: Color = Color(0x00FFFFFF)
