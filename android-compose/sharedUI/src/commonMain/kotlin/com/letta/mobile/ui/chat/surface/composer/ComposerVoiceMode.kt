package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The chat bar's voice mode button where the page has voice mode, [size] across like the Send it sits
 * before in the trailing slot; gone while voice mode runs, when the voice card above the prompt owns
 * its controls (End, the reply's stop). It is distinct from the platform's dictation mic: dictation
 * types into the draft; voice mode is a hands-free conversation.
 */
@Composable
internal fun ComposerVoiceModeButton(
    modifier: Modifier = Modifier,
    size: Dp = LettaDimens.Control.iconButton,
    iconSize: Dp = LettaDimens.Control.icon,
) {
    if (!hasVoiceMode()) return
    val voice = LocalVoiceMode.current ?: return
    VoiceModeButton(voice, size = size, iconSize = iconSize, modifier = modifier)
}

/**
 * The composer's trailing slot holds the voice mode button: the page has voice mode and it is not
 * running (running, the voice card holds its controls).
 */
@Composable
internal fun hasVoiceMode(): Boolean {
    val handle = LocalVoiceMode.current ?: return false
    val state by handle.state.collectAsState()
    return !state.active
}
