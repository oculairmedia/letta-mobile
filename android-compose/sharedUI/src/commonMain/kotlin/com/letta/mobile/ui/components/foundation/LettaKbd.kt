package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens
import com.letta.mobile.ui.theme.tokens.LettaStrokeTokens

/**
 * A key on the keyboard, drawn as a key cap: [key] ("Enter", "Esc", "Ctrl+K") in a small outlined
 * box, for a hint that names keys ("Enter to open") or a menu row's shortcut. Read as its text.
 */
@Composable
fun LettaKbd(key: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(LettaStrokeTokens.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(
            key,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = LettaSpaceTokens.xs, vertical = LettaSpaceTokens.hair),
        )
    }
}
