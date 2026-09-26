package com.letta.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

private val PillHeight: Dp = LettaDimens.Orb.lg

/**
 * Title-row half of the expandable-search pattern. Lives inside the
 * top app bar's `title` slot and renders either the inline collapsed
 * search pill or a close button next to the title. The wide search
 * input field is rendered separately by [ExpandableSearchField] below
 * the top app bar so toggling expanded state does not change the top
 * app bar's height — that prevents the bar's nav icon and actions
 * (gear, overflow) from sliding down with a growing title slot
 * (letta-mobile-guh).
 *
 * Existing call sites do not need to change their `ExpandableTitleSearch`
 * invocation; they just wrap their top app bar in a `Column` and add
 * `ExpandableSearchField` as a sibling below.
 */
@Composable
fun ExpandableTitleSearch(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    titleContent: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search…",
    collapsedHint: String = placeholder.removeSuffix("…"),
    @Suppress("UNUSED_PARAMETER") compactMaxWidth: Dp = 160.dp,
    enabled: Boolean = true,
    clearQueryOnCollapse: Boolean = false,
    openSearchContentDescription: String = "Open search",
    closeSearchContentDescription: String = "Close search",
    @Suppress("UNUSED_PARAMETER") clearSearchContentDescription: String = "Clear search",
    @Suppress("UNUSED_PARAMETER") autoFocus: Boolean = true,
    showCollapseButton: Boolean = true,
    isAppBarCollapsed: Boolean = false,
) {
    LaunchedEffect(isAppBarCollapsed) {
        if (isAppBarCollapsed && expanded) {
            onExpandedChange(false)
        }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            content = titleContent,
        )

        if (expanded) {
            if (showCollapseButton) {
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    enabled = enabled,
                    onClick = {
                        if (clearQueryOnCollapse && query.isNotBlank()) {
                            onClear()
                        }
                        onExpandedChange(false)
                    },
                ) {
                    Icon(
                        imageVector = LettaIcons.Clear,
                        contentDescription = closeSearchContentDescription,
                    )
                }
            }
        } else {
            Surface(
                shape = RoundedCornerShape(LettaDimens.Radius.lg),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = PillHeight)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        contentDescription = openSearchContentDescription
                    }
                    .clickable(enabled = enabled) { onExpandedChange(true) },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = LettaDimens.Space.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = LettaIcons.Search,
                        contentDescription = null,
                        modifier = Modifier.size(LettaDimens.Control.icon),
                    )
                    val collapsedText = query.ifBlank { collapsedHint }
                    if (collapsedText.isNotBlank()) {
                        Spacer(modifier = Modifier.width(LettaDimens.Space.sm))
                        Text(
                            text = collapsedText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Wide search input that animates in below the top app bar when the
 * inline pill is expanded. Render this as a sibling of the top app
 * bar inside the Scaffold's `topBar` slot, wrapped in a Column.
 */
@Composable
fun ExpandableSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "Search…",
    clearSearchContentDescription: String = "Clear search",
    autoFocus: Boolean = true,
    enabled: Boolean = true,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded, enabled) {
        if (expanded && enabled && autoFocus) {
            focusRequester.requestFocus()
        }
    }

    AnimatedVisibility(
        visible = expanded,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut(),
    ) {
        LettaSearchBar(
            query = query,
            onQueryChange = onQueryChange,
            onClear = onClear,
            placeholder = placeholder,
            compact = true,
            searchIconContentDescription = null,
            clearIconContentDescription = clearSearchContentDescription,
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)
                .heightIn(min = PillHeight)
                .focusRequester(focusRequester),
        )
    }
}
