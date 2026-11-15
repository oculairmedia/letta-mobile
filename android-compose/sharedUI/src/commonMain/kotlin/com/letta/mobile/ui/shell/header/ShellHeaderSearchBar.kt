package com.letta.mobile.ui.shell.header

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.chat.surface.CanvasBubbleBackHandler
import com.letta.mobile.ui.components.foundation.LettaButtonIcon
import com.letta.mobile.ui.components.foundation.LettaIconButton
import com.letta.mobile.ui.components.foundation.LettaInlineSearchField
import com.letta.mobile.ui.components.foundation.LettaSearchOptions
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.search.SearchFieldTestTag

/**
 * A phone header in search mode: when the page behind the header is itself
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
        LettaIconButton(
            icon = LettaButtonIcon(LettaIcons.ArrowBack, CLOSE_SEARCH),
            onClick = close,
            modifier = Modifier.testTag(ShellHeaderTags.SEARCH_CLOSE),
        )
        // The shared borderless field: it owns its caret, takes focus when the bar opens and clears in place.
        LettaInlineSearchField(
            query = slot.query,
            onQueryChange = slot.onQuery,
            modifier = Modifier.weight(1f),
            options = LettaSearchOptions(
                placeholder = slot.config.placeholder,
                autoFocus = true,
                fieldModifier = Modifier.testTag(SearchFieldTestTag),
            ),
        )
    }
}

private const val CLOSE_SEARCH = "Close search"
