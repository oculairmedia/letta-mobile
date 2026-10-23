package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.host.ProvideLettaIdiom

/**
 *  (S4): the Touch composer's thinking token on its own, as the page draws it
 * over the bar when the agent has no mascot. For the row parity harness and previews.
 */
@Composable
fun ComposerThinkingSnapshot(state: ChatUiState, modifier: Modifier = Modifier) {
    // A compact window is Touch on every host, and the token only exists over the Touch bar.
    ProvideLettaIdiom(compact = true) {
        Box(modifier) { ComposerThinkingStatus(state) }
    }
}
