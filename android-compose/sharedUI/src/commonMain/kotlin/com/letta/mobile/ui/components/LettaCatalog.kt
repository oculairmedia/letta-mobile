package com.letta.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import com.letta.mobile.ui.components.foundation.LettaPanel
import com.letta.mobile.ui.components.foundation.lettaClickable
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.tokens.LettaTextStyles
import com.letta.mobile.ui.theme.tokens.LettaTextAccents

/*
 * Building blocks for the catalog-style pages (Channels, Skills, Tools, ...) on desktop and
 * Android: an accent card and an empty-state box; their pills are the theme's LettaPill family.
 * The sectioned card grid is a RosterList with columns (letta-mobile-ckmub.3.25). Everything
 * reads the design tokens (MaterialTheme shapes / colorScheme, LettaDimens).
 */

/**
 * A titled card with an accent-tinted leading letter and a two-line description; [footer] sits under
 * the description and [trailing] at the card's end (an add button, a status pill).
 */
@Composable
fun LettaCatalogCard(
    title: String,
    description: String?,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingLabel: String = title.firstOrNull()?.uppercase() ?: "?",
    footer: @Composable ColumnScope.() -> Unit = {},
    trailing: @Composable () -> Unit = {},
) {
    LettaPanel(modifier = modifier) {
        CatalogCardRow(leadingLabel, accent, onClick, trailing) {
            Text(
                title,
                style = LettaTextStyles.titleSemibold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            // Two description lines are always reserved so cards in a grid row line up.
            Text(
                text = description?.takeIf { it.isNotBlank() }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            footer()
        }
    }
}

@Composable
private fun CatalogCardRow(
    leadingLabel: String,
    accent: Color,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .lettaClickable(role = Role.Button, onClick = onClick)
            .padding(LettaDimens.Space.lg),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(LettaDimens.Control.actionButton).clip(MaterialTheme.shapes.small).background(accent.copy(alpha = AVATAR_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Text(leadingLabel, style = LettaTextAccents.monogram, color = accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair), content = body)
        trailing()
    }
}

/** A full-width informational / empty-state box, padded by [contentPadding]. */
@Composable
fun LettaInfoBox(
    message: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = LettaDimens.Space.xxl, vertical = LettaDimens.Space.md),
) {
    Box(modifier.fillMaxWidth().padding(contentPadding)) {
        LettaPanel(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(LettaDimens.Space.lg),
            )
        }
    }
}

/** Standard content padding for a catalog grid on a wide window (the grid itself is a RosterList with columns). */
val LettaCatalogGridPadding: PaddingValues =
    PaddingValues(start = LettaDimens.Space.xxl, end = LettaDimens.Space.xxl, top = LettaDimens.Space.lg, bottom = LettaDimens.Space.lg)

private const val AVATAR_ALPHA = 0.2f
