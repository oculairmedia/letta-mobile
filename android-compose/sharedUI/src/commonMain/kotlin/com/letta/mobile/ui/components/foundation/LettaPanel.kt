package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.letta.mobile.ui.theme.tokens.LettaPanelTone
import com.letta.mobile.ui.theme.tokens.LettaShapes
import com.letta.mobile.ui.theme.tokens.LettaStrokeTokens

/**
 * The one bordered or filled container: a Material [Surface] with the panel corner ([LettaShapes.panel]),
 * a fill and content colour from [tone], and, when [border] is true, a hairline in `outlineVariant`.
 * Replaces the pages' own `Surface`/`Card` panels, which used corners of 6, 8, 10, 12, 14 and 16 dp and
 * borders at alpha 0.3 to 1.0. LettaInlineError and LettaInlineNotice are built on it.
 *
 * Use it for a card of content on a page, a group of controls, or a banner. It adds no padding: the
 * content decides its own insets from the spacing scale.
 */
@Composable
fun LettaPanel(
    modifier: Modifier = Modifier,
    tone: LettaPanelTone = LettaPanelTone.Neutral,
    border: Boolean = true,
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

/** The panel's one border: a hairline in `outlineVariant`, for a container that cannot be a [LettaPanel]. */
@Composable
@ReadOnlyComposable
fun lettaPanelBorder(): BorderStroke = BorderStroke(LettaStrokeTokens.hairline, MaterialTheme.colorScheme.outlineVariant)

/** The fill a [LettaPanel] of this tone draws. */
@Composable
@ReadOnlyComposable
fun LettaPanelTone.containerColor(): Color = when (this) {
    LettaPanelTone.Neutral -> MaterialTheme.colorScheme.surfaceContainer
    LettaPanelTone.Info -> MaterialTheme.colorScheme.secondaryContainer
    LettaPanelTone.Warning -> MaterialTheme.colorScheme.tertiaryContainer
    LettaPanelTone.Error -> MaterialTheme.colorScheme.errorContainer
}

/** The content colour on a [LettaPanel] of this tone. */
@Composable
@ReadOnlyComposable
fun LettaPanelTone.contentColor(): Color = when (this) {
    LettaPanelTone.Neutral -> MaterialTheme.colorScheme.onSurface
    LettaPanelTone.Info -> MaterialTheme.colorScheme.onSecondaryContainer
    LettaPanelTone.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
    LettaPanelTone.Error -> MaterialTheme.colorScheme.onErrorContainer
}
