package com.letta.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * A list row's emphasised name (a slash command in the composer's suggestions): `bodyMedium`
 * semibold, read as `MaterialTheme.typography.chatRowName`. The other emphasised and monospace roles
 * come from LettaTextStyles.
 */
internal val Typography.chatRowName: TextStyle get() = bodyMedium.copy(fontWeight = FontWeight.SemiBold)
