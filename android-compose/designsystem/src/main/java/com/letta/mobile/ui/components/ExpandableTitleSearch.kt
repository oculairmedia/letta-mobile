package com.letta.mobile.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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

private val PillHeight: Dp = 40.dp

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
    clearSearchContentDescription: String = "Clear search",
    autoFocus: Boolean = true,
    showCollapseButton: Boolean = true,
    isAppBarCollapsed: Boolean = false,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(expanded, enabled) {
        if (expanded && enabled && autoFocus) {
            focusRequester.requestFocus()
        }
    }

    LaunchedEffect(isAppBarCollapsed) {
        if (isAppBarCollapsed && expanded) {
            onExpandedChange(false)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                    shape = RoundedCornerShape(24.dp),
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
                        modifier = Modifier.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = LettaIcons.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        val collapsedText = query.ifBlank { collapsedHint }
                        if (collapsedText.isNotBlank()) {
                            Spacer(modifier = Modifier.width(8.dp))
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

        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            LettaSearchBar(
                query = query,
                onQueryChange = onQueryChange,
                onClear = onClear,
                placeholder = placeholder,
                compact = true,
                searchIconContentDescription = null,
                clearIconContentDescription = clearSearchContentDescription,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .heightIn(min = PillHeight)
                    .focusRequester(focusRequester),
            )
        }
    }
}
