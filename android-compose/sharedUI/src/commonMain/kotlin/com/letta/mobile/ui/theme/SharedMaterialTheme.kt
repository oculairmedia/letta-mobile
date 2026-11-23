package com.letta.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letta.mobile.data.model.ThemePreset

/**
 * The Material 3 theme for hosts of the shared UI that have no platform
 * theme of their own (desktop and the web): the colour [preset] (Default is the cool-slate palette from the shared
 * [LettaColorTokens]), the shared [CustomColors], and compact desktop-density [typography] and shapes. Which one is drawn
 * is the person's choice on the host: see [HostAppearanceTheme], which every such host wraps this in.
 */
@Composable
fun SharedMaterialTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    preset: ThemePreset = ThemePreset.DEFAULT,
    typography: Typography = SharedTypography,
    content: @Composable () -> Unit,
) {
    ProvideAccessibilityPolicy {
        val highContrast = LocalHighContrast.current
        val scheme = remember(darkTheme, highContrast, preset) { lettaColorScheme(darkTheme, highContrast, preset) }
        val customColors = remember(scheme) { lettaCustomColors(scheme) }

        CompositionLocalProvider(LocalCustomColors provides customColors) {
            MaterialTheme(
                colorScheme = scheme,
                shapes = SharedShapes,
                typography = typography,
                content = content,
            )
        }
    }
}

/**
 * The Material colour scheme of [preset] for hosts without a platform theme of their own (desktop, web). Default is the
 * Letta cool-slate scheme; every other preset is the preset's own light or dark scheme with the same accent contrast
 * boost Android's `LettaTheme` gives it. Dynamic colour (wallpaper colours) is Android 12+ only and is not drawn here.
 */
fun lettaColorScheme(dark: Boolean, highContrast: Boolean = false, preset: ThemePreset = ThemePreset.DEFAULT): ColorScheme {
    val base = when (preset) {
        ThemePreset.DEFAULT -> lettaScheme(dark, if (dark) LettaDarkRoles else LettaLightRoles)
        else -> presetThemeColors(preset).let { if (dark) it.darkScheme else it.lightScheme }.withLettaContrastBoost()
    }
    return if (highContrast) base.withHighContrast() else base
}

/**
 * The brand palette's colour roles for one theme. [lettaScheme] builds either theme's Material scheme from one of
 * these, so the dark and light schemes are one shape filled from two role tables.
 */
private data class LettaSchemeRoles(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondary: Color,
    val onSecondary: Color,
    val secondaryContainer: Color,
    val tertiary: Color,
    val onTertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val surfaceVariant: Color,
    val onSurfaceVariant: Color,
    val surfaceContainerLowest: Color,
    val surfaceContainerLow: Color,
    val surfaceContainer: Color,
    val surfaceContainerHigh: Color,
    val surfaceContainerHighest: Color,
    val outline: Color,
    val outlineVariant: Color,
    val error: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
)

private val LettaDarkRoles = LettaSchemeRoles(
    primary = Color(LettaColorTokens.DARK_PRIMARY),
    onPrimary = Color(LettaColorTokens.DARK_ON_PRIMARY),
    primaryContainer = Color(LettaColorTokens.DARK_PRIMARY_VARIANT),
    onPrimaryContainer = Color(LettaColorTokens.DARK_ON_PRIMARY_CONTAINER),
    secondary = Color(LettaColorTokens.DARK_SECONDARY),
    onSecondary = Color(LettaColorTokens.DARK_ON_SECONDARY),
    secondaryContainer = Color(LettaColorTokens.DARK_SECONDARY_CONTAINER),
    tertiary = Color(LettaColorTokens.DARK_TERTIARY),
    onTertiary = Color(LettaColorTokens.DARK_ON_TERTIARY),
    tertiaryContainer = Color(LettaColorTokens.DARK_TERTIARY_CONTAINER),
    onTertiaryContainer = Color(LettaColorTokens.DARK_ON_TERTIARY_CONTAINER),
    background = Color(LettaColorTokens.DARK_BACKGROUND),
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
    onError = Color(0xFF000000),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LettaLightRoles = LettaSchemeRoles(
    primary = Color(LettaColorTokens.LIGHT_PRIMARY),
    onPrimary = Color(LettaColorTokens.LIGHT_ON_PRIMARY),
    primaryContainer = Color(LettaColorTokens.LIGHT_PRIMARY).copy(alpha = LIGHT_PRIMARY_CONTAINER_TINT),
    onPrimaryContainer = Color(LettaColorTokens.LIGHT_ON_SURFACE),
    secondary = Color(LettaColorTokens.LIGHT_SECONDARY),
    onSecondary = Color(LettaColorTokens.LIGHT_ON_SECONDARY),
    secondaryContainer = Color(LettaColorTokens.LIGHT_SECONDARY_CONTAINER),
    tertiary = Color(LettaColorTokens.LIGHT_TERTIARY),
    onTertiary = Color(LettaColorTokens.LIGHT_ON_TERTIARY),
    tertiaryContainer = Color(LettaColorTokens.LIGHT_TERTIARY_CONTAINER),
    onTertiaryContainer = Color(LettaColorTokens.LIGHT_ON_TERTIARY_CONTAINER),
    background = Color(LettaColorTokens.LIGHT_BACKGROUND),
    surface = Color(LettaColorTokens.LIGHT_SURFACE),
    onSurface = Color(LettaColorTokens.LIGHT_ON_SURFACE),
    surfaceVariant = Color(LettaColorTokens.LIGHT_SURFACE_VARIANT),
    onSurfaceVariant = Color(LettaColorTokens.LIGHT_ON_SURFACE_VARIANT),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(LettaColorTokens.LIGHT_SURFACE),
    surfaceContainer = Color(LettaColorTokens.LIGHT_SURFACE_VARIANT),
    surfaceContainerHigh = Color(LettaColorTokens.LIGHT_SURFACE_CONTAINER),
    surfaceContainerHighest = Color(LettaColorTokens.LIGHT_SURFACE_CONTAINER_HIGHEST),
    outline = Color(LettaColorTokens.LIGHT_OUTLINE),
    outlineVariant = Color(LettaColorTokens.LIGHT_SURFACE_CONTAINER),
    error = Color(LettaColorTokens.LIGHT_ERROR),
    onError = Color(LettaColorTokens.LIGHT_ON_ERROR),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/**
 * The brand palette's Material scheme for one theme: [roles] over Material's own dark or light scheme, which keeps
 * the roles the brand does not name (inverse, scrim, fixed) at Material's values for that theme.
 */
private fun lettaScheme(dark: Boolean, roles: LettaSchemeRoles): ColorScheme =
    (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = roles.primary,
        onPrimary = roles.onPrimary,
        primaryContainer = roles.primaryContainer,
        onPrimaryContainer = roles.onPrimaryContainer,
        surfaceTint = roles.primary,
        secondary = roles.secondary,
        onSecondary = roles.onSecondary,
        secondaryContainer = roles.secondaryContainer,
        onSecondaryContainer = roles.onSurface,
        tertiary = roles.tertiary,
        onTertiary = roles.onTertiary,
        tertiaryContainer = roles.tertiaryContainer,
        onTertiaryContainer = roles.onTertiaryContainer,
        background = roles.background,
        onBackground = roles.onSurface,
        surface = roles.surface,
        onSurface = roles.onSurface,
        surfaceVariant = roles.surfaceVariant,
        onSurfaceVariant = roles.onSurfaceVariant,
        surfaceContainerLowest = roles.surfaceContainerLowest,
        surfaceContainerLow = roles.surfaceContainerLow,
        surfaceContainer = roles.surfaceContainer,
        surfaceContainerHigh = roles.surfaceContainerHigh,
        surfaceContainerHighest = roles.surfaceContainerHighest,
        outline = roles.outline,
        outlineVariant = roles.outlineVariant,
        error = roles.error,
        onError = roles.onError,
        errorContainer = roles.errorContainer,
        onErrorContainer = roles.onErrorContainer,
    )

/** The Letta [CustomColors] derived from a [lettaColorScheme] (fixed brand status/agent colours). */
fun lettaCustomColors(scheme: ColorScheme): CustomColors =
    CustomColors(
        userBubbleBgColor = lettaUserBubble(scheme),
        agentBubbleBgColor = scheme.surfaceContainerLow,
        reasoningBubbleBgColor = scheme.tertiaryContainer.copy(alpha = 0.72f),
        toolBubbleBgColor = scheme.surfaceContainer,
        systemMessageColor = scheme.surfaceContainerHigh,
        dateSeparatorColor = scheme.onSurfaceVariant,
        textPrimary = scheme.onSurface,
        textSecondary = scheme.onSurfaceVariant,
        textDisabled = scheme.onSurface.copy(alpha = TEXT_DISABLED_ALPHA),
        textLink = scheme.primary,
        textOnPrimary = scheme.onPrimary,
        errorTextColor = scheme.error,
        warningTextColor = warningInk(scheme),
        warningContainerColor = warningContainer(scheme),
        harmonizedWarning = warningInk(scheme),
        harmonizedWarningContainer = warningContainer(scheme),
        successColor = Color(0xFF46C08F),
        onSuccessColor = Color(0xFF06302B),
        runningColor = Color(0xFFE0A458),
        onRunningColor = Color(0xFF2B1B00),
        voiceYouColor = Color(if (scheme.isLight()) LettaColorTokens.LIGHT_VOICE_YOU else LettaColorTokens.DARK_VOICE_YOU),
        voiceAgentColor = Color(if (scheme.isLight()) LettaColorTokens.LIGHT_VOICE_AGENT else LettaColorTokens.DARK_VOICE_AGENT),
        agentAColor = Color(0xFF8B7CF0),
        agentBColor = Color(0xFF4C9AFF),
        agentCColor = Color(0xFFE36FB3),
        onSurfaceMutedColor = scheme.onSurfaceVariant.copy(alpha = LettaDimens.Alpha.muted),
        categoryPersonaColor = lettaCategoryPersona(scheme),
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

/**
 * The user's chat bubble on [scheme]: a neutral surface, never the vermilion. On the brand palette it is the named
 * token (LettaColorTokens.DARK_USER_BUBBLE, a grey step above the agent's bubble, or LIGHT_USER_BUBBLE, a soft
 * grey); on any other scheme (a preset, Android's wallpaper colours) it is that scheme's surface lifted toward its
 * ink by the same small step, so it stays neutral there too. Text on it is `onSurface`. Every client (desktop and
 * web through [lettaCustomColors], Android through its custom colours and LettaChatTheme, the shared timeline rows)
 * reads the bubble from here.
 */
fun lettaUserBubble(scheme: ColorScheme): Color = when (scheme.surface) {
    Color(LettaColorTokens.DARK_SURFACE) -> Color(LettaColorTokens.DARK_USER_BUBBLE)
    Color(LettaColorTokens.LIGHT_SURFACE) -> Color(LettaColorTokens.LIGHT_USER_BUBBLE)
    else -> scheme.onSurface
        .copy(alpha = if (scheme.isLight()) LIGHT_USER_BUBBLE_LIFT else DARK_USER_BUBBLE_LIFT)
        .compositeOver(scheme.surface)
}

/** The persona memory category's colour on [scheme]: the brand's vermilion family, deepened on a light theme. */
fun lettaCategoryPersona(scheme: ColorScheme): Color =
    Color(if (scheme.isLight()) LettaColorTokens.LIGHT_CATEGORY_PERSONA else LettaColorTokens.DARK_CATEGORY_PERSONA)

/**
 * The primary as small text on [scheme]'s surfaces (the context meter's warning label). On the brand palette it is
 * the vermilion deepened on light ([LettaColorTokens.LIGHT_PRIMARY_TEXT]) or lifted on dark (DARK_PRIMARY_TEXT) to
 * 4.5:1 on every surface level; any other scheme (a preset, Android's wallpaper colours) keeps its own primary.
 */
fun lettaPrimaryText(scheme: ColorScheme): Color = when (scheme.primary) {
    Color(LettaColorTokens.LIGHT_PRIMARY) -> Color(LettaColorTokens.LIGHT_PRIMARY_TEXT)
    Color(LettaColorTokens.DARK_PRIMARY) -> Color(LettaColorTokens.DARK_PRIMARY_TEXT)
    else -> scheme.primary
}

/** The amber warning ink of [scheme]'s brightness ([LettaColorTokens.LIGHT_WARNING] / DARK_WARNING). */
private fun warningInk(scheme: ColorScheme): Color =
    Color(if (scheme.isLight()) LettaColorTokens.LIGHT_WARNING else LettaColorTokens.DARK_WARNING)

/** The amber warning container of [scheme]'s brightness, which [warningInk] reads on. */
private fun warningContainer(scheme: ColorScheme): Color =
    Color(if (scheme.isLight()) LettaColorTokens.LIGHT_WARNING_CONTAINER else LettaColorTokens.DARK_WARNING_CONTAINER)

/**
 * The compact type scale for desktop and the web: Material 3's roles, 2 to 5 sp smaller, for more words
 * per line on a monitor. Android (LettaTheme, Inter) and desktop's phone preview keep Material 3's
 * phone sizes; the split is by screen density, not drift, so it is not unified here.
 */
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

/**
 * The one corner scale, on every host: Android's LettaTheme, desktop (both its own and its phone
 * preview metrics) and the web all pass these shapes, so `MaterialTheme.shapes.medium` is the same
 * corner everywhere. small, medium and large are [LettaDimens.Radius] sm, md and lg, so converting a
 * `RoundedCornerShape(LettaDimens.Radius.x)` to the matching role moves nothing.
 *
 * extraSmall: menus, text fields' Material default, tooltips. small: chips, pills, small controls.
 * medium: panels, cards, list rows (LettaShapes.panel). large: composer, popups. extraLarge: dialogs
 * and sheets.
 */
val SharedShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(LettaDimens.Radius.sm),
    medium = RoundedCornerShape(LettaDimens.Radius.md),
    large = RoundedCornerShape(LettaDimens.Radius.lg),
    extraLarge = RoundedCornerShape(16.dp),
)

/**
 * How strongly the light theme's primary container is tinted with primary. It is a fill under content
 * (the user's bubble, a selected row), not content, so it sits far below the content alpha floor.
 */
private const val LIGHT_PRIMARY_CONTAINER_TINT = 0.16f

/** How far a non-brand scheme's user bubble lifts its surface toward its ink, per theme. */
private const val LIGHT_USER_BUBBLE_LIFT = 0.1f
private const val DARK_USER_BUBBLE_LIFT = 0.05f

/** Whether [this] is a light scheme: its surface is light. */
private fun ColorScheme.isLight(): Boolean = surface.luminance() > LIGHT_SURFACE_LUMINANCE

private const val LIGHT_SURFACE_LUMINANCE = 0.5f

/** How strongly disabled text keeps `onSurface` (customColors.textDisabled). */
private const val TEXT_DISABLED_ALPHA = 0.5f
