package com.letta.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.collections.immutable.persistentListOf

/**
 * Shared Material 3 Theme for Desktop (JVM) and Web (Wasm).
 * Sourced directly from shared [LettaColorTokens] and [CustomColors] in `:sharedLogic`.
 */
@Composable
fun SharedMaterialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) darkColorScheme(
        primary = Color(LettaColorTokens.DARK_PRIMARY),
        onPrimary = Color(0xFF06302B),
        primaryContainer = Color(LettaColorTokens.DARK_PRIMARY_VARIANT),
        onPrimaryContainer = Color(0xFFE6F4F1),
        secondary = Color(LettaColorTokens.TEAL_ACCENT),
        onSecondary = Color(0xFF06302B),
        secondaryContainer = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_HIGH),
        onSecondaryContainer = Color(LettaColorTokens.DARK_ON_SURFACE),
        tertiary = Color(LettaColorTokens.CYAN_ACCENT),
        onTertiary = Color(0xFF002B30),
        tertiaryContainer = Color(0xFF143C42),
        onTertiaryContainer = Color(0xFFCFF6FB),
        background = Color(LettaColorTokens.DARK_BACKGROUND),
        onBackground = Color(LettaColorTokens.DARK_ON_SURFACE),
        surface = Color(LettaColorTokens.DARK_SURFACE),
        onSurface = Color(LettaColorTokens.DARK_ON_SURFACE),
        surfaceVariant = Color(LettaColorTokens.DARK_SURFACE_VARIANT),
        onSurfaceVariant = Color(LettaColorTokens.DARK_ON_SURFACE_VARIANT),
        surfaceContainerLowest = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_LOWEST),
        surfaceContainerLow = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_LOW),
        surfaceContainer = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_DEFAULT),
        surfaceContainerHigh = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_HIGH),
        surfaceContainerHighest = Color(LettaColorTokens.DARK_SURFACE_CONTAINER_HIGHEST),
        outline = Color(LettaColorTokens.DARK_OUTLINE),
        outlineVariant = Color(LettaColorTokens.DARK_OUTLINE_VARIANT),
        error = Color(LettaColorTokens.DARK_ERROR),
        errorContainer = Color(0xFF93000A),
        onError = Color(0xFF000000),
        onErrorContainer = Color(0xFFFFDAD6),
    ) else lightColorScheme(
        primary = Color(LettaColorTokens.LIGHT_PRIMARY),
        onPrimary = Color.White,
        primaryContainer = Color(LettaColorTokens.LIGHT_PRIMARY).copy(alpha = 0.16f),
        onPrimaryContainer = Color(LettaColorTokens.LIGHT_ON_SURFACE),
        secondary = Color(LettaColorTokens.LIGHT_PRIMARY),
        onSecondary = Color.White,
        secondaryContainer = Color(LettaColorTokens.LIGHT_SURFACE_CONTAINER),
        onSecondaryContainer = Color(LettaColorTokens.LIGHT_ON_SURFACE),
        tertiary = Color(0xFF007D8A),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFC7F1F5),
        onTertiaryContainer = Color(0xFF002F34),
        background = Color(LettaColorTokens.LIGHT_BACKGROUND),
        onBackground = Color(LettaColorTokens.LIGHT_ON_SURFACE),
        surface = Color(LettaColorTokens.LIGHT_SURFACE),
        onSurface = Color(LettaColorTokens.LIGHT_ON_SURFACE),
        surfaceVariant = Color(LettaColorTokens.LIGHT_SURFACE_VARIANT),
        onSurfaceVariant = Color(LettaColorTokens.LIGHT_ON_SURFACE_VARIANT),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(LettaColorTokens.LIGHT_SURFACE),
        surfaceContainer = Color(LettaColorTokens.LIGHT_SURFACE_VARIANT),
        surfaceContainerHigh = Color(LettaColorTokens.LIGHT_SURFACE_CONTAINER),
        surfaceContainerHighest = Color(0xFFD2D9E2),
        outline = Color(LettaColorTokens.LIGHT_OUTLINE),
        outlineVariant = Color(LettaColorTokens.LIGHT_SURFACE_CONTAINER),
        error = Color(LettaColorTokens.LIGHT_ERROR),
        onError = Color(LettaColorTokens.LIGHT_ON_ERROR),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
    )

    val customColors = remember(scheme, darkTheme) {
        CustomColors(
            userBubbleBgColor = scheme.primaryContainer,
            agentBubbleBgColor = scheme.surfaceContainerLow,
            reasoningBubbleBgColor = scheme.tertiaryContainer.copy(alpha = 0.72f),
            toolBubbleBgColor = scheme.surfaceContainer,
            systemMessageColor = scheme.surfaceContainerHigh,
            dateSeparatorColor = scheme.onSurfaceVariant,
            textPrimary = scheme.onSurface,
            textSecondary = scheme.onSurfaceVariant,
            textDisabled = scheme.onSurface.copy(alpha = 0.5f),
            textLink = scheme.primary,
            textOnPrimary = scheme.onPrimary,
            errorTextColor = scheme.error,
            successColor = Color(0xFF46C08F),
            onSuccessColor = Color(0xFF06302B),
            runningColor = Color(0xFFE0A458),
            onRunningColor = Color(0xFF2B1B00),
            agentAColor = Color(0xFF8B7CF0),
            agentBColor = Color(0xFF4C9AFF),
            agentCColor = Color(0xFFE36FB3),
            agentGradientColors = persistentListOf(
                Color(0xFFF0A03C), Color(0xFFE0457B),
                Color(0xFFE0457B), Color(0xFF8E5CFF),
                Color(0xFF3FA0F0), Color(0xFF3FE0C0),
                Color(0xFF7AD08F), Color(0xFF3FA0A0),
                Color(0xFF8E7CFF), Color(0xFF3F6EF0),
                Color(0xFF3FC0D0), Color(0xFF3F90A0),
            ),
            onSurfaceMutedColor = scheme.onSurfaceVariant.copy(alpha = 0.72f),
            categoryPersonaColor = Color(LettaColorTokens.DARK_CATEGORY_PERSONA),
            categoryHumanColor = Color(LettaColorTokens.DARK_CATEGORY_HUMAN),
            categoryOnboardingColor = Color(LettaColorTokens.DARK_CATEGORY_ONBOARDING),
            categoryProjectColor = Color(LettaColorTokens.DARK_CATEGORY_PROJECT),
            categoryArchivalColor = Color(LettaColorTokens.DARK_CATEGORY_ARCHIVAL),
            onlineColor = Color(0xFF46C08F),
            offlineColor = Color(0xFFCF6679),
            reconnectingColor = Color(0xFFE0A458),
            iconPrimary = scheme.onSurface,
            iconSecondary = scheme.onSurfaceVariant,
            iconAccent = scheme.primary,
            listItemContainerColor = scheme.surfaceContainer,
            borderDefault = scheme.outlineVariant,
            borderFocused = scheme.primary,
            borderCritical = scheme.error,
        )
    }

    CompositionLocalProvider(LocalCustomColors provides customColors) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = SharedShapes,
            typography = SharedTypography,
            content = content,
        )
    }
}

val SharedTypography: Typography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(fontSize = 44.sp, lineHeight = 52.sp),
        displayMedium = base.displayMedium.copy(fontSize = 36.sp, lineHeight = 44.sp),
        displaySmall = base.displaySmall.copy(fontSize = 28.sp, lineHeight = 36.sp),
        headlineLarge = base.headlineLarge.copy(fontSize = 26.sp, lineHeight = 32.sp),
        headlineMedium = base.headlineMedium.copy(fontSize = 22.sp, lineHeight = 28.sp),
        headlineSmall = base.headlineSmall.copy(fontSize = 19.sp, lineHeight = 26.sp),
        titleLarge = base.titleLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
        titleMedium = base.titleMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
        titleSmall = base.titleSmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        bodyLarge = base.bodyLarge.copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp),
        labelLarge = base.labelLarge.copy(fontSize = 12.sp, lineHeight = 16.sp),
        labelMedium = base.labelMedium.copy(fontSize = 11.sp, lineHeight = 15.sp),
        labelSmall = base.labelSmall.copy(fontSize = 10.sp, lineHeight = 14.sp),
    )
}

val SharedShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(16.dp),
)
