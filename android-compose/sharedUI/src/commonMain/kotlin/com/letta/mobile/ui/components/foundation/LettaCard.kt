package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.theme.tokens.LettaShapes

/*
 * The card family. Every container a page draws is one of three, so two cards on one screen never
 * pick their own fill, border or corner:
 *
 * - LettaCard: a card of content on a page (a settings section, a provider, an agent-authored card).
 * - LettaNestedCard: a surface inside a card or dialog (a link block, a confirmation, a list item).
 * - LettaPanel: a panel whose fill says something (info, warning, error), or a bordered control group.
 *
 * LettaCard and LettaNestedCard give their content the theme's content colour for their fill, as
 * Material's Card does. None of them adds padding: the content sets its insets from the spacing scale. Floating chrome
 * (toolbars, popovers, panes, tooltips) is not a card and keeps its own frame.
 */

/**
 * A card of content on a page: `surfaceContainer` with the hairline [lettaPanelBorder], on the card
 * corner ([LettaShapes.card]). The Settings sections are drawn by it.
 *
 * [accent] replaces the hairline for a card whose state is its edge (a tool request's risk); leave it
 * null otherwise.
 */
@Composable
fun LettaCard(
    modifier: Modifier = Modifier,
    accent: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = LettaShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = accent ?: lettaPanelBorder(),
        content = content,
    )
}

/** A [LettaCard] that is one press target: the whole card runs [onClick], and none of it while not [enabled]. */
@Composable
fun LettaCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = LettaShapes.card,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = lettaPanelBorder(),
        content = content,
    )
}

/**
 * A surface inside a [LettaCard] or a dialog: one tonal step above the card (`surfaceContainerHigh`),
 * with no border, on the panel corner ([LettaShapes.panel]) so it nests inside the card's larger one.
 */
@Composable
fun LettaNestedCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = LettaShapes.panel,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        content = content,
    )
}
