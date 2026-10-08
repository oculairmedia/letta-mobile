package com.letta.mobile.desktop.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letta.mobile.desktop.DesktopIconButton
import com.letta.mobile.desktop.DesktopTextField
import com.letta.mobile.ui.components.LettaCatalogCard
import com.letta.mobile.ui.components.LettaCatalogGridPadding
import com.letta.mobile.ui.components.LettaChipTab
import com.letta.mobile.ui.components.LettaInfoBox
import com.letta.mobile.ui.components.LettaPill
import com.letta.mobile.ui.components.lettaCardGrid
import com.letta.mobile.ui.theme.LettaDimens

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
        modifier = modifier.fillMaxWidth().padding(start = LettaDimens.Space.xxl, end = LettaDimens.Space.xxl, top = LettaDimens.Space.lg, bottom = LettaDimens.Space.sm),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (query != null) {
                Box(Modifier.width(220.dp)) {
                    DesktopTextField(value = query, onValueChange = onQuery, placeholder = searchPlaceholder, modifier = Modifier.fillMaxWidth())
                }
            }
            actions()
        }
        if (chips != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), verticalAlignment = Alignment.CenterVertically, content = chips)
        }
    }
}

/** Icon-only refresh action for catalog/page headers (no button chrome). */
@Composable
internal fun DesktopRefreshAction(onRefresh: () -> Unit, enabled: Boolean = true) {
    DesktopIconButton(
        imageVector = Icons.Outlined.Refresh,
        contentDescription = "Refresh",
        onClick = onRefresh,
        enabled = enabled,
    )
}

/** A small, tokenized filter/segment chip used in catalog headers. */
@Composable
internal fun DesktopChipTab(text: String, active: Boolean, onClick: () -> Unit) =
    LettaChipTab(text = text, active = active, onClick = onClick)

/** A thin vertical divider for separating chip groups in a header. */
@Composable
internal fun DesktopChipDivider() {
    Box(Modifier.padding(horizontal = LettaDimens.Space.xs).width(1.dp).height(LettaDimens.Space.xl).background(MaterialTheme.colorScheme.outlineVariant))
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
) = LettaCatalogCard(
    title = title,
    description = description,
    accent = accent,
    onClick = onClick,
    modifier = modifier,
    leadingLabel = leadingLabel,
    trailing = trailing,
)

/** Render labelled [sections] as a 2-column card grid inside a LazyColumn ([lettaCardGrid]). */
internal fun <T> LazyListScope.desktopCardGrid(
    sections: List<Pair<String, List<T>>>,
    keyOf: (T) -> String,
    card: @Composable (T, Modifier) -> Unit,
) = lettaCardGrid(sections = sections, keyOf = keyOf, columns = 2, card = card)

/** Standard content padding for a catalog grid LazyColumn. */
internal val DesktopCatalogGridPadding: PaddingValues = LettaCatalogGridPadding

/** A small tinted pill (tags, status, type labels). */
@Composable
internal fun DesktopPill(text: String, color: Color) = LettaPill(text = text, color = color)

/** A full-width informational/empty-state box, padded to align with the grid. */
@Composable
internal fun DesktopInfoBox(message: String) = LettaInfoBox(message = message)
