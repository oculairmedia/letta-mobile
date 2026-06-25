package com.letta.mobile.desktop.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import com.letta.mobile.desktop.DesktopTextField

/**
 * Shared building blocks for the catalog-style pages (Skills, Tools, Channels,
 * …) so they read as one design: a compact header with the title + optional
 * search/actions and a chips row underneath, a 2-column card grid grouped into
 * labelled sections, and the cards/pills themselves. Everything references the
 * desktop design tokens (MaterialTheme.shapes / colorScheme / customColors) —
 * no hard-coded radii.
 */
@Composable
internal fun DesktopCatalogHeader(
    title: String,
    modifier: Modifier = Modifier,
    query: String? = null,
    onQuery: (String) -> Unit = {},
    searchPlaceholder: String = "Search",
    chips: (@Composable RowScope.() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (query != null) {
                Box(Modifier.width(220.dp)) {
                    DesktopTextField(value = query, onValueChange = onQuery, placeholder = searchPlaceholder, modifier = Modifier.fillMaxWidth())
                }
            }
            actions()
        }
        if (chips != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = chips)
        }
    }
}

/** A small, tokenized filter/segment chip used in catalog headers. */
@Composable
internal fun DesktopChipTab(text: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** A thin vertical divider for separating chip groups in a header. */
@Composable
internal fun DesktopChipDivider() {
    Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

/**
 * A catalog card: an accent-colored leading avatar (a letter by default),
 * title + description, and an optional [trailing] slot (e.g. an add/install
 * button or a status pill).
 */
@Composable
internal fun DesktopCatalogCard(
    title: String,
    description: String?,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingLabel: String = title.firstOrNull()?.uppercase() ?: "?",
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(MaterialTheme.shapes.small).background(accent.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(leadingLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = accent)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

/**
 * Render labelled [sections] as a 2-column card grid inside a LazyColumn. Each
 * section gets a caption then rows of (up to) two cards built by [card].
 */
internal fun <T> LazyListScope.desktopCardGrid(
    sections: List<Pair<String, List<T>>>,
    keyOf: (T) -> String,
    card: @Composable (T, Modifier) -> Unit,
) {
    sections.forEach { (label, sectionItems) ->
        item(key = "section-$label") {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(items = sectionItems.chunked(2), key = { keyOf(it.first()) }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { item -> card(item, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Standard content padding for a catalog grid LazyColumn. */
internal val DesktopCatalogGridPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 16.dp)

/** A small tinted pill (tags, status, type labels). */
@Composable
internal fun DesktopPill(text: String, color: Color) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.18f)),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** A full-width informational/empty-state box, padded to align with the grid. */
@Composable
internal fun DesktopInfoBox(message: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
        }
    }
}
