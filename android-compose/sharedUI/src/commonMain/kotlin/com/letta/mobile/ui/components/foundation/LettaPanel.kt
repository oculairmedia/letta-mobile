package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.letta.mobile.ui.theme.tokens.LettaPanelTone
import com.letta.mobile.ui.theme.tokens.LocalLettaCustomColors
import com.letta.mobile.ui.theme.tokens.LocalLettaHighContrast
import com.letta.mobile.ui.theme.tokens.LettaShapes
import com.letta.mobile.ui.theme.tokens.LettaStrokeTokens

/**
 * The toned container of the card family (see LettaCard.kt): a Material [Surface] with the panel corner
 * ([LettaShapes.panel]) and a fill and content colour from [tone]. It is borderless by default: the tonal
 * surface step ([tone]'s fill over the page) separates a panel, the same language the cards, sheets and
 * menus speak; only a panel that floats on a same-tone background (a card docked over the chat surface,
 * controls over the canvas) opts in with [border] and draws the barely-there hairline of [lettaPanelBorder].
 * LettaInlineError and LettaInlineNotice are built on it.
 *
 * Use it for a panel whose fill says something (info, warning, error), a group of controls, or a banner;
 * a card of content on a page is a LettaCard. It adds no padding: the content decides its own insets
 * from the spacing scale.
 */
@Composable
fun LettaPanel(
    modifier: Modifier = Modifier,
    tone: LettaPanelTone = LettaPanelTone.Neutral,
    border: Boolean = false,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = LettaShapes.panel,
        color = tone.containerColor(),
        contentColor = tone.contentColor(),
        border = if (border) lettaPanelBorder() else null,
        content = content,
    )
}

/**
 * The panel family's one border: a hairline in `outlineVariant`, for a container that cannot be a [LettaPanel].
 * In normal contrast it carries [PANEL_BORDER_ALPHA] of the role — a line you can find but not see, for the
 * floating surfaces that sit on a same-tone background; when the person or the OS asks for high contrast it
 * draws the whole (theme-boosted) `outlineVariant`, so the edge a high-contrast reader leans on stays strong.
 */
@Composable
@ReadOnlyComposable
fun lettaPanelBorder(): BorderStroke {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val colour = if (LocalLettaHighContrast.current) outline else outline.copy(alpha = PANEL_BORDER_ALPHA)
    return BorderStroke(LettaStrokeTokens.hairline, colour)
}

/**
 * How much of `outlineVariant` the panel hairline carries in normal contrast. The tonal step does the
 * separating; the line only firms up an edge a fill cannot, so on the brand surfaces it sits about 5/255
 * away from the fill — findable when you look for it, invisible in a glance. High contrast drops the
 * alpha, never the role.
 */
private const val PANEL_BORDER_ALPHA = 0.28f

/** The fill a [LettaPanel] of this tone draws. */
@Composable
@ReadOnlyComposable
fun LettaPanelTone.containerColor(): Color = when (this) {
    LettaPanelTone.Neutral -> MaterialTheme.colorScheme.surfaceContainer
    LettaPanelTone.Info -> MaterialTheme.colorScheme.secondaryContainer
    LettaPanelTone.Warning -> LocalLettaCustomColors.current.warningContainerColor.orElse(MaterialTheme.colorScheme.tertiaryContainer)
    LettaPanelTone.Error -> MaterialTheme.colorScheme.errorContainer
}

/** This colour, or [fallback] where the theme leaves it unset (a host theme with no Letta colours). */
private fun Color.orElse(fallback: Color): Color = if (this == Color.Unspecified) fallback else this

/** The content colour on a [LettaPanel] of this tone: a warning's is the amber ink, which reads on its container. */
@Composable
@ReadOnlyComposable
fun LettaPanelTone.contentColor(): Color = when (this) {
    LettaPanelTone.Neutral -> MaterialTheme.colorScheme.onSurface
    LettaPanelTone.Info -> MaterialTheme.colorScheme.onSecondaryContainer
    LettaPanelTone.Warning -> LocalLettaCustomColors.current.warningTextColor.orElse(MaterialTheme.colorScheme.onTertiaryContainer)
    LettaPanelTone.Error -> MaterialTheme.colorScheme.onErrorContainer
}
