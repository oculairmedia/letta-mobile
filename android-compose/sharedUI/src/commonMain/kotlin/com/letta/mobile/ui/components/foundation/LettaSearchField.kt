package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaShapes
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens

/**
 * What every shared text input adds to its field: the shell provides its keyboard-shortcut hub's
 * text-input marker here, so a focused field keeps its keystrokes from the shell's chords. Nothing
 * by default (a host without shortcuts).
 */
val LocalLettaTextInputModifier = staticCompositionLocalOf<Modifier> { Modifier }

/** The shared search fields' placeholder when a caller passes none. */
const val LETTA_SEARCH_PLACEHOLDER = "Search"

/** The shared search fields' clear button, as a screen reader says it. */
const val LETTA_SEARCH_CLEAR = "Clear search"

/**
 * The one search input a page shows on its own: a Material [OutlinedTextField] with a magnifier, a
 * clear button once something is typed, one line, and the panel's corner ([LettaShapes.field]).
 * Replaces the pages' own `OutlinedTextField` search boxes (Home, Channels, Memory files, Providers,
 * Settings), which used corners from 4 dp to a pill and glyphs at 13, 18 and 24 dp.
 *
 * Use [LettaInlineSearchField] instead when the field sits inside a container that already draws a
 * frame (a picker, a popup, a header bar). [modifier] goes on the field, so a caller's test tag finds
 * it; [focusRequester] lets a caller focus it, and [autoFocus] focuses it on first composition.
 */
@Composable
fun LettaSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = LETTA_SEARCH_PLACEHOLDER,
    onClear: () -> Unit = { onQueryChange("") },
    focusRequester: FocusRequester = remember { FocusRequester() },
    autoFocus: Boolean = false,
) {
    AutoFocus(focusRequester, autoFocus)
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(placeholder, maxLines = 1) },
        leadingIcon = { LettaIcon(LettaIcons.Search, contentDescription = null) },
        trailingIcon = { if (query.isNotEmpty()) LettaIconButton(LettaIcons.Clear, LETTA_SEARCH_CLEAR, onClear) },
        singleLine = true,
        shape = LettaShapes.field,
        modifier = modifier.focusRequester(focusRequester).then(LocalLettaTextInputModifier.current),
    )
}

/**
 * The borderless search row for a field inside a container that already draws a frame (a picker's
 * top row, a search popup, the shell header): a magnifier, the text, and a clear button once something
 * is typed, at the scale's glyph and button sizes. Replaces LettaPickerSearchField's own row, the
 * search popup's field and the header search bar's field.
 *
 * The caret is owned here ([TextFieldValue]), so the first keystroke lands after the cursor even when
 * the caller's state round-trips the text. [fieldModifier] goes on the text field itself (tags);
 * [placeholder] is also its screen-reader description.
 */
@Composable
fun LettaInlineSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = LETTA_SEARCH_PLACEHOLDER,
    onClear: () -> Unit = { onQueryChange("") },
    focusRequester: FocusRequester = remember { FocusRequester() },
    autoFocus: Boolean = false,
    fieldModifier: Modifier = Modifier,
) {
    AutoFocus(focusRequester, autoFocus)
    Row(
        modifier = modifier
            .fillMaxWidth()
            // Tall enough for the clear button, so the row does not jump when it shows.
            .heightIn(min = LettaControlTokens.iconButton + LettaSpaceTokens.sm * 2)
            .padding(horizontal = LettaSpaceTokens.lg, vertical = LettaSpaceTokens.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.md),
    ) {
        LettaIcon(LettaIcons.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        InlineSearchText(
            query = query,
            onQueryChange = onQueryChange,
            placeholder = placeholder,
            modifier = fieldModifier.weight(1f).focusRequester(focusRequester),
        )
        if (query.isNotEmpty()) LettaIconButton(LettaIcons.Clear, LETTA_SEARCH_CLEAR, onClear)
    }
}

@Composable
private fun InlineSearchText(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    var fieldValue by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    if (fieldValue.text != query) fieldValue = TextFieldValue(query, TextRange(query.length))
    val textStyle = MaterialTheme.typography.bodyLarge.merge(TextStyle(color = MaterialTheme.colorScheme.onSurface))
    BasicTextField(
        value = fieldValue,
        onValueChange = {
            fieldValue = it
            if (it.text != query) onQueryChange(it.text)
        },
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier.then(LocalLettaTextInputModifier.current).semantics { contentDescription = placeholder },
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                inner()
            }
        },
    )
}

@Composable
private fun AutoFocus(focusRequester: FocusRequester, enabled: Boolean) {
    if (enabled) LaunchedEffect(focusRequester) { runCatching { focusRequester.requestFocus() } }
}
