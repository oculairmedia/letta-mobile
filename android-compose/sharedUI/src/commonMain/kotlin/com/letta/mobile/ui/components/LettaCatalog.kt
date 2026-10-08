package com.letta.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.ui.theme.LettaDimens

/*
 * Building blocks for the catalog-style pages (Channels, Skills, Tools, ...) on desktop and
 * Android: an accent card, tinted pills, an empty-state box and a sectioned card grid. Everything
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
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant.copy(alpha = CARD_BORDER_ALPHA), MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(LettaDimens.Space.lg),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(LettaDimens.Control.actionButton).clip(MaterialTheme.shapes.small).background(accent.copy(alpha = AVATAR_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Text(leadingLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
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
        trailing()
    }
}

/** A small tinted pill (tags, status, type labels). */
@Composable
fun LettaPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = PILL_FILL_ALPHA),
        contentColor = color,
        border = BorderStroke(LettaDimens.Stroke.hairline, color.copy(alpha = PILL_BORDER_ALPHA)),
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
        )
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
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = INFO_FILL_ALPHA),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant.copy(alpha = INFO_BORDER_ALPHA)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(LettaDimens.Space.lg))
        }
    }
}

/** Standard content padding for a catalog grid on a wide window. */
val LettaCatalogGridPadding: PaddingValues =
    PaddingValues(start = LettaDimens.Space.xxl, end = LettaDimens.Space.xxl, top = LettaDimens.Space.lg, bottom = LettaDimens.Space.lg)

/**
 * Renders labelled [sections] as a card grid of [columns] inside a LazyColumn: each section gets a
 * caption, then rows of up to [columns] cards built by [card].
 */
fun <T> LazyListScope.lettaCardGrid(
    sections: List<Pair<String, List<T>>>,
    keyOf: (T) -> String,
    columns: Int = 2,
    card: @Composable (T, Modifier) -> Unit,
) {
    sections.forEach { (label, sectionItems) ->
        item(key = "section-$label") {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(items = sectionItems.chunked(columns), key = { keyOf(it.first()) }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg)) {
                row.forEach { item -> card(item, Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val CARD_BORDER_ALPHA = 0.6f
private const val AVATAR_ALPHA = 0.2f
private const val PILL_FILL_ALPHA = 0.12f
private const val PILL_BORDER_ALPHA = 0.18f
private const val INFO_FILL_ALPHA = 0.42f
private const val INFO_BORDER_ALPHA = 0.48f
