package com.letta.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class CustomColors(
    // Chat bubbles
    val userBubbleBgColor: Color = Color.Unspecified,
    val agentBubbleBgColor: Color = Color.Unspecified,
    val reasoningBubbleBgColor: Color = Color.Unspecified,
    val toolBubbleBgColor: Color = Color.Unspecified,
    val systemMessageColor: Color = Color.Unspecified,
    val dateSeparatorColor: Color = Color.Unspecified,

    // Text
    val textPrimary: Color = Color.Unspecified,
    val textSecondary: Color = Color.Unspecified,
    val textDisabled: Color = Color.Unspecified,
    val textLink: Color = Color.Unspecified,
    val textOnPrimary: Color = Color.Unspecified,

    // Status
    val errorTextColor: Color = Color.Unspecified,
    val errorContainerColor: Color = Color.Unspecified,
    val warningTextColor: Color = Color.Unspecified,
    val warningContainerColor: Color = Color.Unspecified,
    val successColor: Color = Color.Unspecified,
    val successContainerColor: Color = Color.Unspecified,

    // Connection status
    val onlineColor: Color = Color.Unspecified,
    val offlineColor: Color = Color.Unspecified,
    val reconnectingColor: Color = Color.Unspecified,

    // Icons
    val iconPrimary: Color = Color.Unspecified,
    val iconSecondary: Color = Color.Unspecified,
    val iconAccent: Color = Color.Unspecified,

    // Borders
    val borderDefault: Color = Color.Unspecified,
    val borderFocused: Color = Color.Unspecified,
    val borderCritical: Color = Color.Unspecified,
)

val LocalCustomColors = staticCompositionLocalOf { CustomColors() }

val lightCustomColors = CustomColors(
    userBubbleBgColor = LightPrimary,
    agentBubbleBgColor = Color(0xFFF0F0F0),
    reasoningBubbleBgColor = Color(0xFFE8EAF6),
    toolBubbleBgColor = Color(0xFFF3E5F5),
    systemMessageColor = Color(0xFFE3F2FD),
    dateSeparatorColor = Color(0xFF9E9E9E),
    textPrimary = Color(0xFF1A1A1A),
    textSecondary = Color(0xFF616161),
    textDisabled = Color(0xFFBDBDBD),
    textLink = Color(0xFF0B57D0),
    textOnPrimary = Color(0xFFFFFFFF),
    errorTextColor = Color(0xFFB3261E),
    errorContainerColor = Color(0xFFF9DEDC),
    warningTextColor = Color(0xFF7C5800),
    warningContainerColor = Color(0xFFFFF0C3),
    successColor = Color(0xFF146C2E),
    successContainerColor = Color(0xFFD4EDDA),
    onlineColor = Color(0xFF2E7D32),
    offlineColor = Color(0xFFC62828),
    reconnectingColor = Color(0xFFF9A825),
    iconPrimary = Color(0xFF424242),
    iconSecondary = Color(0xFF757575),
    iconAccent = LightPrimary,
    borderDefault = Color(0xFFE0E0E0),
    borderFocused = LightPrimary,
    borderCritical = Color(0xFFB3261E),
)

val darkCustomColors = CustomColors(
    userBubbleBgColor = DarkPrimary,
    agentBubbleBgColor = DarkSurfaceContainer,
    reasoningBubbleBgColor = Color(0xFF1A237E),
    toolBubbleBgColor = Color(0xFF4A148C),
    systemMessageColor = Color(0xFF1A2332),
    dateSeparatorColor = Color(0xFF757575),
    textPrimary = Color(0xFFE0E0E0),
    textSecondary = Color(0xFFBDBDBD),
    textDisabled = Color(0xFF616161),
    textLink = Color(0xFF8AB4F8),
    textOnPrimary = Color(0xFF000000),
    errorTextColor = Color(0xFFCF6679),
    errorContainerColor = Color(0xFF93000A),
    warningTextColor = Color(0xFFFFD740),
    warningContainerColor = Color(0xFF4A3800),
    successColor = Color(0xFF81C784),
    successContainerColor = Color(0xFF1B4332),
    onlineColor = Color(0xFF81C784),
    offlineColor = Color(0xFFEF5350),
    reconnectingColor = Color(0xFFFFD740),
    iconPrimary = Color(0xFFE0E0E0),
    iconSecondary = Color(0xFF9E9E9E),
    iconAccent = DarkPrimary,
    borderDefault = Color(0xFF424242),
    borderFocused = DarkPrimary,
    borderCritical = Color(0xFFCF6679),
)

val MaterialTheme.customColors: CustomColors
    @Composable
    @ReadOnlyComposable
    get() = LocalCustomColors.current
