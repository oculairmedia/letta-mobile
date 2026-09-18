package com.letta.mobile.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The one search surface.
 *
 * WHY
 * ---
 * This app had grown four separate search inputs — the command palette, the
 * desktop tab-strip picker, the new-conversation field and Home — each with its
 * own field, its own row layout and its own idea of contrast and spacing. They
 * differ only in where they are drawn and which parts are switched on, so they
 * are one component with a [LettaSearchConfig], not four components.
 *
 * [LettaSearchBody] is the whole implementation. [LettaSearchInline],
 * [LettaSearchDropdownContent] and [LettaSearchPopover] are thin hosts around
 * it — a popover and a dropdown render the *same* body, so a fix to row
 * layout or keyboard handling lands everywhere at once. Adding a fifth surface
 * should mean writing a config, not a composable.
 *
 * Filtering is the caller's, through `TextMatch` / `CommandPalette` in
 * sharedLogic. This package renders what it is given and never sorts or
 * filters, so a host can search a database, an index or an in-memory list
 * without this code caring.
 *
 * All dimensions come from [LettaDimens]. This is the reference implementation
 * for that too: no literal in this file.
 */
@Composable
fun LettaSearchBody(
    query: String,
    onQueryChange: (String) -> Unit,
    sections: List<LettaSearchSection>,
    onRowSelected: (LettaSearchRow) -> Unit,
    modifier: Modifier = Modifier,
    config: LettaSearchConfig = LettaSearchConfig(),
) {
    val focusRequester = remember { FocusRequester() }
    if (config.autoFocus) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }

    Column(modifier = modifier) {
        LettaSearchField(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = config.placeholder,
            focusRequester = focusRequester,
        )

        if (config.scopes.isNotEmpty() || config.toggle != null) {
            LettaSearchScopeRow(config)
        }

        config.actions.forEach { action ->
            LettaSearchActionRow(action = action, query = query)
        }

        val rowCount = sections.sumOf { it.rows.size }
        if (rowCount == 0 && config.actions.isEmpty()) {
            Text(
                text = config.emptyText(query),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = LettaDimens.Space.md,
                    vertical = LettaDimens.Space.md,
                ),
            )
            return@Column
        }

        val listModifier = config.maxResultsHeight
            ?.let { Modifier.fillMaxWidth().heightIn(max = it) }
            ?: Modifier.fillMaxWidth()

        if (config.lazyResults) {
            LazyColumn(modifier = listModifier) {
                sections.forEach { section ->
                    if (config.showSectionHeaders && section.title.isNotBlank()) {
                        item(key = "header-${section.title}") {
                            LettaSearchSectionHeader(section.title)
                        }
                    }
                    items(
                        count = section.rows.size,
                        key = { index -> "${section.title}-${section.rows[index].id}" },
                    ) { index ->
                        LettaSearchResultRow(
                            row = section.rows[index],
                            onClick = { onRowSelected(section.rows[index]) },
                        )
                    }
                }
            }
        } else {
            // Eager column: see LettaSearchConfig.lazyResults. A DropdownMenu
            // measures its content's intrinsic width and a lazy layout will not
            // report one, so the menu collapses to nothing.
            Column(modifier = listModifier.verticalScroll(rememberScrollState())) {
                sections.forEach { section ->
                    if (config.showSectionHeaders && section.title.isNotBlank()) {
                        LettaSearchSectionHeader(section.title)
                    }
                    section.rows.forEach { row ->
                        LettaSearchResultRow(row = row, onClick = { onRowSelected(row) })
                    }
                }
            }
        }
    }
}

/**
 * The field. Borderless by design: every host already draws a container (a
 * popup surface, a dropdown, a bordered panel), and a bordered input inside one
 * reads as a second frame.
 */
@Composable
private fun LettaSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    focusRequester: FocusRequester,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(LettaDimens.Control.iconSm),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun LettaSearchScopeRow(config: LettaSearchConfig) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        config.scopes.forEach { scope ->
            val selected = scope.id == config.selectedScopeId
            Surface(
                onClick = { config.onScopeSelected(scope.id) },
                shape = RoundedCornerShape(LettaDimens.Radius.sm),
                color = if (selected) {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                } else {
                    Color.Transparent
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ) {
                Text(
                    text = scope.label,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(
                        horizontal = LettaDimens.Space.md,
                        vertical = LettaDimens.Space.xs,
                    ),
                )
            }
        }
        config.toggle?.let { toggle ->
            Box(modifier = Modifier.weight(WEIGHT_FILL))
            Checkbox(checked = toggle.checked, onCheckedChange = toggle.onCheckedChange)
            Text(
                text = toggle.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LettaSearchActionRow(action: LettaSearchAction, query: String) {
    Surface(
        onClick = { action.onInvoke(query) },
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = LettaDimens.Space.md,
                vertical = LettaDimens.Space.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            action.icon?.let { icon ->
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(LettaDimens.Control.icon),
                )
            }
            Text(text = action.labelForQuery(query), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LettaSearchSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = LettaDimens.Space.md,
            end = LettaDimens.Space.md,
            top = LettaDimens.Space.md,
            bottom = LettaDimens.Space.xs,
        ),
    )
}

@Composable
private fun LettaSearchResultRow(row: LettaSearchRow, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = LettaDimens.Space.md,
                vertical = LettaDimens.Space.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            LettaSearchRowLeading(row.leading)
            Column(modifier = Modifier.weight(WEIGHT_FILL)) {
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                row.sublabel?.let { sublabel ->
                    Text(
                        text = sublabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            row.meta?.let { meta ->
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The orb case is intentionally a plain coloured tile here rather than the live
 * `AgentOrb`: this package must stay free of the mascot host so it can be used
 * in surfaces (and tests) that do not provide one. Hosts that want live
 * mascots supply [LettaSearchLeading.Icon] or wrap the row themselves.
 */
@Composable
private fun LettaSearchRowLeading(leading: LettaSearchLeading) {
    when (leading) {
        is LettaSearchLeading.None -> Unit
        is LettaSearchLeading.Icon -> Icon(
            imageVector = leading.image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(LettaDimens.Control.icon),
        )
        is LettaSearchLeading.Orb -> Box(
            modifier = Modifier
                .size(LettaDimens.Orb.sm)
                .background(
                    MaterialTheme.colorScheme.surfaceContainerHighest,
                    RoundedCornerShape(LettaDimens.Radius.md),
                ),
        )
    }
}

/**
 * Inline: the body with no chrome of its own, for hosts that already have a
 * panel — a sidebar section, a page header, the Home screen.
 */
@Composable
fun LettaSearchInline(
    query: String,
    onQueryChange: (String) -> Unit,
    sections: List<LettaSearchSection>,
    onRowSelected: (LettaSearchRow) -> Unit,
    modifier: Modifier = Modifier,
    config: LettaSearchConfig = LettaSearchConfig(),
) {
    LettaSearchBody(
        query = query,
        onQueryChange = onQueryChange,
        sections = sections,
        onRowSelected = onRowSelected,
        modifier = modifier.fillMaxWidth(),
        config = config,
    )
}

/**
 * Dropdown: the body sized for an anchored menu. The caller owns the
 * `DropdownMenu` (and therefore its anchoring and dismissal) and puts this
 * inside it, so this package does not have to know about popup positioning on
 * two platforms.
 */
@Composable
fun LettaSearchDropdownContent(
    query: String,
    onQueryChange: (String) -> Unit,
    sections: List<LettaSearchSection>,
    onRowSelected: (LettaSearchRow) -> Unit,
    modifier: Modifier = Modifier,
    config: LettaSearchConfig = LettaSearchConfig(),
) {
    LettaSearchBody(
        query = query,
        onQueryChange = onQueryChange,
        sections = sections,
        onRowSelected = onRowSelected,
        modifier = modifier.widthIn(min = DropdownMinWidth, max = DropdownMaxWidth),
        config = config.copy(
            maxResultsHeight = config.maxResultsHeight ?: DropdownMaxResultsHeight,
            lazyResults = false,
        ),
    )
}

/**
 * Popover: a scrim over the window with the body centred in a raised surface.
 * Clicking the scrim dismisses; clicking the panel does not.
 */
@Composable
fun LettaSearchPopover(
    query: String,
    onQueryChange: (String) -> Unit,
    sections: List<LettaSearchSection>,
    onRowSelected: (LettaSearchRow) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    config: LettaSearchConfig = LettaSearchConfig(),
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = ScrimAlpha))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = modifier
                .widthIn(min = PopoverMinWidth, max = PopoverMaxWidth)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            shape = RoundedCornerShape(LettaDimens.Radius.lg),
            color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = PopoverElevation,
        ) {
            LettaSearchBody(
                query = query,
                onQueryChange = onQueryChange,
                sections = sections,
                onRowSelected = onRowSelected,
                config = config.copy(
                    maxResultsHeight = config.maxResultsHeight ?: PopoverMaxResultsHeight,
                ),
            )
        }
    }
}

private const val WEIGHT_FILL = 1f
private const val ScrimAlpha = 0.45f

// Panel geometry: reading-width decisions, not points on the spacing scale, so
// they are named here rather than tokenised.
private val DropdownMinWidth = 280.dp
private val DropdownMaxWidth = 360.dp
private val DropdownMaxResultsHeight = 320.dp
private val PopoverMinWidth = 520.dp
private val PopoverMaxWidth = 640.dp
private val PopoverMaxResultsHeight = 420.dp
private val PopoverElevation = 8.dp
