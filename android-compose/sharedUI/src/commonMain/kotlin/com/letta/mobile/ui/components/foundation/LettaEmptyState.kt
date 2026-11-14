package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.letta.mobile.ui.theme.tokens.LettaComponentDimens
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens

/**
 * The one empty state for a page or pane with nothing to show: an optional [icon], an optional
 * [title], the [text] saying why it is empty or what to do, and an optional [action] (a button), all
 * centred. Replaces the pages' private hint, empty and message composables.
 *
 * It fills the width it is given and centres itself in it; give [modifier] `fillMaxSize()` (and a
 * scroll, when a pull-to-refresh must still reach it) to centre it in a whole pane. For a quiet one-line
 * note inside a section ("No chats"), use LettaEmptyHint instead.
 */
@Composable
fun LettaEmptyState(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(LettaSpaceTokens.xl),
        verticalArrangement = Arrangement.spacedBy(LettaSpaceTokens.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let {
            LettaIcon(it, contentDescription = null, size = LettaComponentDimens.emptyStateIcon, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        title?.let {
            Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}
