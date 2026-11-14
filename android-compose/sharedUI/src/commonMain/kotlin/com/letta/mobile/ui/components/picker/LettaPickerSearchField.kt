package com.letta.mobile.ui.components.picker

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.components.foundation.LettaDivider
import com.letta.mobile.ui.components.foundation.LettaInlineSearchField
import com.letta.mobile.ui.components.foundation.LettaSearchOptions

/**
 * The borderless search row pinned at the top of a picker (and the Models sheet), with a hairline
 * under it: the shared [LettaInlineSearchField] and [LettaDivider]. [fieldModifier] goes on the text
 * field itself (tags); [PickerSearch.autoFocus] puts the caret in it on open, so typing filters at once.
 */
@Composable
fun LettaPickerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    search: PickerSearch,
    fieldModifier: Modifier = Modifier,
) {
    LettaInlineSearchField(
        query = query,
        onQueryChange = onQueryChange,
        options = LettaSearchOptions(placeholder = search.placeholder, autoFocus = search.autoFocus, fieldModifier = fieldModifier),
    )
    LettaDivider()
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
