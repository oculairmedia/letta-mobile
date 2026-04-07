package com.letta.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class CustomColors(
    val userBubbleBgColor: Color = Color.Unspecified,
    val agentBubbleBgColor: Color = Color.Unspecified,
    val reasoningBubbleBgColor: Color = Color.Unspecified,
    val toolBubbleBgColor: Color = Color.Unspecified,
    val linkColor: Color = Color.Unspecified,
    val errorTextColor: Color = Color.Unspecified,
    val errorContainerColor: Color = Color.Unspecified,
    val warningTextColor: Color = Color.Unspecified,
    val warningContainerColor: Color = Color.Unspecified,
    val successColor: Color = Color.Unspecified,
)

val LocalCustomColors = staticCompositionLocalOf { CustomColors() }

val lightCustomColors = CustomColors(
    userBubbleBgColor = LightPrimary,
    agentBubbleBgColor = Color(0xFFF0F0F0),
    reasoningBubbleBgColor = Color(0xFFE8EAF6),
    toolBubbleBgColor = Color(0xFFF3E5F5),
    linkColor = Color(0xFF0B57D0),
    errorTextColor = Color(0xFFB3261E),
    errorContainerColor = Color(0xFFF9DEDC),
    warningTextColor = Color(0xFF7C5800),
    warningContainerColor = Color(0xFFFFF0C3),
    successColor = Color(0xFF146C2E),
)

val darkCustomColors = CustomColors(
    userBubbleBgColor = DarkPrimary,
    agentBubbleBgColor = DarkSurfaceContainer,
    reasoningBubbleBgColor = Color(0xFF1A237E),
    toolBubbleBgColor = Color(0xFF4A148C),
    linkColor = Color(0xFF8AB4F8),
    errorTextColor = Color(0xFFCF6679),
    errorContainerColor = Color(0xFF93000A),
    warningTextColor = Color(0xFFFFD740),
    warningContainerColor = Color(0xFF4A3800),
    successColor = Color(0xFF81C784),
)

val MaterialTheme.customColors: CustomColors
    @Composable
    @ReadOnlyComposable
    get() = LocalCustomColors.current
