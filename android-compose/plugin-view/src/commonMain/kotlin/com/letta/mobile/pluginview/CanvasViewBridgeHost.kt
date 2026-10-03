package com.letta.mobile.pluginview

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import com.letta.mobile.data.plugin.PluginDisplayMode
import com.letta.mobile.data.plugin.view.ViewBridgeHost
import com.letta.mobile.data.plugin.view.ViewHostContext
import com.letta.mobile.data.plugin.view.ViewLink
import com.letta.mobile.data.plugin.view.ViewTheme
import kotlin.concurrent.Volatile

/**
 * The board's side of one live view's bridge: the context the composition last worked out (theme,
 * Material colours as CSS variables, the element, settings, locale), and links through the
 * platform's handler. Display modes other than inline and growing the frame on `view.resize` are
 * the live-view integration's (letta-mobile-s416w.15); until then the view stays inline at its frame.
 */
internal class CanvasViewBridgeHost(
    initial: ViewHostContext,
    private val openUri: (String) -> Unit,
) : ViewBridgeHost {
    /** Written by the composition, read by the bridge's coroutines. */
    @Volatile
    var current: ViewHostContext = initial

    override fun context(): ViewHostContext = current

    override fun onResize(width: Double, height: Double) = Unit

    override suspend fun requestDisplayMode(mode: PluginDisplayMode): PluginDisplayMode = PluginDisplayMode.INLINE

    override suspend fun openLink(link: ViewLink) = openUri(link.url)
}

/** The Material colour roles a page gets as `--md-sys-color-*` variables, so it can match the board. */
internal object ViewCssVariables {
    private const val PREFIX = "--md-sys-color-"
    private const val RGB_MASK = 0xFFFFFF
    private const val HEX_DIGITS = 6
    private const val HALF = 0.5f

    fun of(colors: ColorScheme): Map<String, String> = listOf(
        "primary" to colors.primary,
        "on-primary" to colors.onPrimary,
        "primary-container" to colors.primaryContainer,
        "on-primary-container" to colors.onPrimaryContainer,
        "secondary" to colors.secondary,
        "on-secondary" to colors.onSecondary,
        "tertiary" to colors.tertiary,
        "on-tertiary" to colors.onTertiary,
        "background" to colors.background,
        "on-background" to colors.onBackground,
        "surface" to colors.surface,
        "on-surface" to colors.onSurface,
        "surface-variant" to colors.surfaceVariant,
        "on-surface-variant" to colors.onSurfaceVariant,
        "outline" to colors.outline,
        "error" to colors.error,
        "on-error" to colors.onError,
    ).associate { (role, color) -> PREFIX + role to hex(color) }

    fun theme(colors: ColorScheme): ViewTheme = if (colors.background.luminance() < HALF) ViewTheme.DARK else ViewTheme.LIGHT

    private fun hex(color: Color): String = "#" + (color.toArgb() and RGB_MASK).toString(16).padStart(HEX_DIGITS, '0')
}
