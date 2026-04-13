package com.letta.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
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

    val freshAccent: Color = Color.Unspecified,
    val onFreshAccent: Color = Color.Unspecified,
    val freshAccentContainer: Color = Color.Unspecified,
    val onFreshAccentContainer: Color = Color.Unspecified,

    val listItemContainerColor: Color = Color.Unspecified,

    // Selection (complementary to primary — warm↔cool inversion)
    val selectionContainer: Color = Color.Unspecified,
    val onSelectionContainer: Color = Color.Unspecified,
    val selectionIndicator: Color = Color.Unspecified,

    // Borders
    val borderDefault: Color = Color.Unspecified,
    val borderFocused: Color = Color.Unspecified,
    val borderCritical: Color = Color.Unspecified,
)

val LocalCustomColors = staticCompositionLocalOf { CustomColors() }

val MaterialTheme.customColors: CustomColors
    @Composable
    @ReadOnlyComposable
    get() = LocalCustomColors.current

val CustomColors.listItemColors: ListItemColors
    @Composable
    get() = ListItemDefaults.colors(containerColor = listItemContainerColor)
