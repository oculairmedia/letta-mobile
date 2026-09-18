package com.letta.mobile.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.letta.mobile.ui.chat.AgentOrb
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
/**
 * Which parts of the scaffold to draw.
 *
 * The anchored expression needs the field in the header and everything else in
 * a panel below it. It selects parts of THIS composable rather than owning a
 * second copy of the list — there is exactly one implementation of the field,
 * the scope row, the actions, the empty state and the results.
 */
private enum class LettaSearchParts { All, FieldOnly, PanelOnly }

@Composable
fun LettaSearchBody(
    query: String,
    onQueryChange: (String) -> Unit,
    sections: List<LettaSearchSection>,
    onRowSelected: (LettaSearchRow) -> Unit,
    modifier: Modifier = Modifier,
    config: LettaSearchConfig = LettaSearchConfig(),
) {
    LettaSearchScaffold(
        query = query,
        onQueryChange = onQueryChange,
        sections = sections,
        onRowSelected = onRowSelected,
        modifier = modifier,
        config = config,
        parts = LettaSearchParts.All,
    )
}

@Composable
private fun LettaSearchScaffold(
    query: String,
    onQueryChange: (String) -> Unit,
    sections: List<LettaSearchSection>,
    onRowSelected: (LettaSearchRow) -> Unit,
    modifier: Modifier,
    config: LettaSearchConfig,
    parts: LettaSearchParts,
) {
    val focusRequester = remember { FocusRequester() }
    if (config.autoFocus && parts != LettaSearchParts.PanelOnly) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }

    Column(modifier = modifier) {
        if (parts != LettaSearchParts.PanelOnly) {
            config.title?.let { title ->
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(
                        start = LettaDimens.Space.lg,
                        top = LettaDimens.Space.md,
                        end = LettaDimens.Space.lg,
                    ),
                )
            }
            LettaSearchField(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = config.placeholder,
                prefix = config.fieldPrefix,
                focusRequester = focusRequester,
            )
        }
        if (parts == LettaSearchParts.FieldOnly) return@Column

        if (config.scopes.isNotEmpty()) {
            LettaSearchScopeRow(config)
        }
        config.toggle?.let { LettaSearchToggleRow(it) }

        config.actions.forEach { action ->
            LettaSearchActionRow(action = action, query = query)
        }

        if (config.recents.isNotEmpty() && query.isBlank()) {
            LettaSearchRecentsStrip(
                title = config.recentsTitle,
                rows = config.recents,
                onRowSelected = onRowSelected,
            )
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
    prefix: String?,
    focusRequester: FocusRequester,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs)
            // Claim focus on press, ourselves, on the Initial pass.
            //
            // Desktop hosts this field inside Jewel's title bar, which is a
            // NATIVE caption region: a press there is the window's drag-to-move
            // gesture. Buttons survive that because a click is all they need,
            // but a text field needs FOCUS, and the caption handler never moves
            // Compose focus — so the field could be clicked and still never
            // receive a keystroke. Asking for focus before anyone else sees the
            // event fixes it without consuming the press, so caret placement and
            // selection still work normally once focused.
            .onFocusChanged { focused = it.isFocused }
            .pointerInput(focusRequester) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!focused) runCatching { focusRequester.requestFocus() }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        if (prefix == null) {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(LettaDimens.Control.iconSm),
            )
        } else {
            // The prefix replaces the magnifier: "To:" already says what the
            // field is for, and both together read as two labels on one input.
            Text(
                text = prefix,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // TextFieldValue, not the String overload.
        //
        // With the String overload the caret is derived from a value that round
        // trips through the caller's state, so the first keystroke landed with
        // the selection still at index 0 and the character appeared AFTER the
        // cursor. Owning the selection here and only resyncing when the caller
        // changes the text out from under us (a clear, a pick) keeps the caret
        // where the typist put it.
        var fieldValue by remember { mutableStateOf(TextFieldValue(query)) }
        if (fieldValue.text != query) {
            fieldValue = TextFieldValue(query, TextRange(query.length))
        }
        BasicTextField(
            value = fieldValue,
            onValueChange = {
                fieldValue = it
                if (it.text != query) onQueryChange(it.text)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .testTag(SearchFieldTestTag),
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
            .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.hair),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
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
    }
}

/**
 * The filter toggle, on its own line under the scope buttons.
 *
 * It shared the scope row until the panel narrowed, at which point there was no
 * horizontal room left and the label wrapped to one character per line. A
 * checkbox and a sentence do not belong in a row that is already competing for
 * width with a variable number of scope buttons.
 */
@Composable
private fun LettaSearchToggleRow(toggle: LettaSearchToggle) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { toggle.onCheckedChange(!toggle.checked) },
            )
            .padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.hair),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Checkbox(checked = toggle.checked, onCheckedChange = toggle.onCheckedChange)
        Text(
            text = toggle.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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

/** Recently used entries as orb-over-name tiles, scrolling horizontally. */
@Composable
private fun LettaSearchRecentsStrip(
    title: String,
    rows: List<LettaSearchRow>,
    onRowSelected: (LettaSearchRow) -> Unit,
) {
    LettaSearchSectionHeader(title)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        rows.forEach { row ->
            Column(
                modifier = Modifier
                    .width(RecentTileWidth)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onRowSelected(row) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            ) {
                when (val leading = row.leading) {
                    is LettaSearchLeading.Orb -> AgentOrb(
                        index = leading.orbIndex,
                        size = LettaDimens.Orb.lg,
                        cornerRadius = LettaDimens.Radius.md,
                        agentId = leading.agentId,
                    )
                    else -> Box(modifier = Modifier.size(LettaDimens.Orb.lg))
                }
                Text(
                    text = row.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
            top = LettaDimens.Space.sm,
            bottom = LettaDimens.Space.hair,
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
                vertical = LettaDimens.Space.xs,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
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
 * The orb case draws the real [AgentOrb], so a result row shows the agent's
 * live mascot exactly as the rail, the sidebar and the chat header do. That is
 * the whole point of one search component: an agent looks like itself
 * everywhere, not like a grey square in search and a mascot elsewhere.
 *
 * `AgentOrb` degrades on its own when no mascot host or identity is registered
 * (it falls back to the gradient), so this costs nothing in surfaces or tests
 * that do not provide one.
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
        is LettaSearchLeading.Orb -> AgentOrb(
            index = leading.orbIndex,
            size = LettaDimens.Orb.sm,
            cornerRadius = LettaDimens.Radius.md,
            agentId = leading.agentId,
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

/**
 * Anchored field: the header expression.
 *
 * The header box is a TRIGGER, not the input. Clicking it opens a focusable
 * [Popup] directly beneath, and the real field lives in there.
 *
 * That indirection is forced by the host, not preference. Nucleus's title bar
 * applies `focusProperties { canFocus = false }` to its whole subtree on
 * Windows and Linux, so Tab navigation cannot wander into the window-drag
 * region — which also means NOTHING inside it can take keyboard focus. In
 * Compose a parent's `canFocus = false` deactivates the subtree and a
 * descendant cannot re-enable it, so an inline field there can be clicked and
 * will never receive a keystroke. A Popup composes in its own layer, outside
 * that focus scope, so the field inside one focuses normally.
 *
 * The trigger mirrors the live query, so it still reads as one control: you
 * click where the text is and type where the text appears.
 *
 * It is the same [LettaSearchScaffold] as every other expression.
 */
@Composable
fun LettaSearchAnchoredField(
    query: String,
    onQueryChange: (String) -> Unit,
    sections: List<LettaSearchSection>,
    onRowSelected: (LettaSearchRow) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    config: LettaSearchConfig = LettaSearchConfig(),
) {
    var expanded by remember { mutableStateOf(false) }

    val overlaying = expanded && config.anchorMode == LettaSearchAnchorMode.Overlay
    Box(modifier = modifier) {
        // While the panel overlays it, the trigger keeps its space but draws
        // nothing: the panel's own field is sitting on top of it, and showing
        // both meant the same query rendered twice, once truncated.
        LettaSearchTrigger(
            query = query,
            placeholder = config.placeholder,
            onClick = { expanded = true },
            visible = !overlaying,
        )
        if (expanded) {
            Popup(
                alignment = Alignment.TopStart,
                offset = IntOffset(0, 0),
                onDismissRequest = {
                    expanded = false
                    onDismiss()
                },
                // Focusable, unlike the panel this replaced: taking focus is the
                // entire reason the input lives out here.
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    modifier = Modifier
                        .padding(
                            top = if (config.anchorMode == LettaSearchAnchorMode.Below) {
                                AnchoredPanelTopInset
                            } else {
                                0.dp
                            },
                        )
                        .widthIn(min = AnchoredPanelMinWidth, max = AnchoredPanelMaxWidth),
                    shape = RoundedCornerShape(LettaDimens.Radius.lg),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = PopoverElevation,
                ) {
                    LettaSearchScaffold(
                        query = query,
                        onQueryChange = onQueryChange,
                        sections = sections,
                        onRowSelected = { row ->
                            expanded = false
                            onRowSelected(row)
                        },
                        modifier = Modifier,
                        config = config.copy(
                            autoFocus = true,
                            maxResultsHeight = config.maxResultsHeight ?: AnchoredPanelMaxHeight,
                        ),
                        parts = LettaSearchParts.All,
                    )
                }
            }
        }
    }
}

/** The header's stand-in for the field: shows the live query, opens the real one. */
@Composable
private fun LettaSearchTrigger(
    query: String,
    placeholder: String,
    onClick: () -> Unit,
    visible: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (visible) 1f else 0f)
            .clip(RoundedCornerShape(LettaDimens.Radius.sm))
            .clickable(onClick = onClick)
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
        Text(
            text = query.ifBlank { placeholder },
            style = MaterialTheme.typography.bodyMedium,
            color = if (query.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Stable handle for tests and automation to type into any Letta search. */
const val SearchFieldTestTag: String = "letta-search-field"

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
private val AnchoredPanelMinWidth = 280.dp
private val AnchoredPanelMaxWidth = 380.dp
private val AnchoredPanelMaxHeight = 280.dp
private val AnchoredPanelTopInset = 36.dp
private val RecentTileWidth = 64.dp
