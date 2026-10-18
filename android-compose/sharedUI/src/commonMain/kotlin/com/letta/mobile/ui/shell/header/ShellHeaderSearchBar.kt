package com.letta.mobile.ui.shell.header

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.letta.mobile.ui.chat.surface.CanvasBubbleBackHandler
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.search.SearchFieldTestTag
import com.letta.mobile.ui.shell.shortcuts.shortcutTextInput
import com.letta.mobile.ui.theme.LettaDimens

/**
 * A phone header in search mode (letta-mobile-06whj): when the page behind the header is itself
 * the result surface (Home), the header becomes a full-width search bar in place rather than
 * dropping a floating box over the page. Back (the arrow, the system Back or Escape) leaves search
 * mode and clears the query, so the page's filter never outlives the bar that set it; the clear
 * button empties the query and stays in search mode.
 */
@Composable
internal fun ShellHeaderSearchBar(slot: SearchSlot, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val close = {
        slot.onQuery("")
        slot.onDismiss()
        onClose()
    }
    CanvasBubbleBackHandler(enabled = true, onBack = close)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ShellHeaderTags.SEARCH_BAR)
            .onPreviewKeyEvent { event ->
                val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                if (escape) close()
                escape
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = close, modifier = Modifier.testTag(ShellHeaderTags.SEARCH_CLOSE)) {
            Icon(LettaIcons.ArrowBack, contentDescription = CLOSE_SEARCH)
        }
        SearchBarField(slot.query, slot.onQuery, slot.config.placeholder, Modifier.weight(1f))
        if (slot.query.isNotEmpty()) {
            IconButton(onClick = { slot.onQuery("") }, modifier = Modifier.testTag(ShellHeaderTags.SEARCH_CLEAR)) {
                Icon(LettaIcons.Close, contentDescription = CLEAR_SEARCH)
            }
        }
    }
}

/** The bar's input. It owns its caret (see LettaSearchField) and takes focus when the bar opens. */
@Composable
private fun SearchBarField(query: String, onQuery: (String) -> Unit, placeholder: String, modifier: Modifier) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) { runCatching { focusRequester.requestFocus() } }
    var value by remember { mutableStateOf(TextFieldValue(query, TextRange(query.length))) }
    if (value.text != query) value = TextFieldValue(query, TextRange(query.length))
    BasicTextField(
        value = value,
        onValueChange = {
            value = it
            if (it.text != query) onQuery(it.text)
        },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .padding(horizontal = LettaDimens.Space.xs)
            .focusRequester(focusRequester)
            .shortcutTextInput()
            .testTag(SearchFieldTestTag),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                inner()
            }
        },
    )
}

private const val CLOSE_SEARCH = "Close search"
private const val CLEAR_SEARCH = "Clear search"
