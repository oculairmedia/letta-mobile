package com.letta.mobile.ui.components.picker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The borderless search row pinned at the top of a picker (and the Models sheet), with a hairline
 * under it. [fieldModifier] goes on the text field itself (tags); [PickerSearch.autoFocus] puts the
 * caret in it on open, so typing filters at once.
 */
@Composable
fun LettaPickerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    search: PickerSearch,
    fieldModifier: Modifier = Modifier,
) {
    val placeholder = search.placeholder
    val focusRequester = remember { FocusRequester() }
    if (search.autoFocus) {
        LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Icon(
            imageVector = LettaIcons.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(LettaDimens.Control.icon),
        )
        val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = fieldModifier
                .weight(1f)
                .focusRequester(focusRequester)
                .semantics { contentDescription = placeholder },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(LettaDimens.Control.iconButton)) {
                Icon(LettaIcons.Clear, contentDescription = LettaPickerStrings.CLEAR_SEARCH, modifier = Modifier.size(LettaDimens.Control.icon))
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** The picker's own words; a surface passes its own placeholder and empty text. */
object LettaPickerStrings {
    const val SEARCH = "Search"
    const val CLEAR_SEARCH = "Clear search"
    const val BACK = "Back"
    const val NOTHING_HERE = "Nothing here yet"
    const val LOADING = "Loading…"

    fun noMatch(query: String): String = "No matches for \"$query\""
}
