package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.chat.voice.LocalVoiceMode
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The chat bar's voice mode button where the page has voice mode, [size] across like the Send it sits
 * before in the trailing slot. It is distinct from the platform's dictation mic: dictation types into
 * the draft; voice mode is a hands-free conversation.
 */
@Composable
internal fun ComposerVoiceModeButton(
    modifier: Modifier = Modifier,
    size: Dp = LettaDimens.Control.iconButton,
    iconSize: Dp = LettaDimens.Control.icon,
) {
    val voice = LocalVoiceMode.current ?: return
    VoiceModeButton(voice, size = size, iconSize = iconSize, modifier = modifier)
}

/** The page has voice mode, so the composer's trailing slot holds its button. */
@Composable
internal fun hasVoiceMode(): Boolean = LocalVoiceMode.current != null

/** Voice mode's one-line status, directly above the chat bar, while it is not off. */
@Composable
internal fun ComposerVoiceStatus() {
    val voice = LocalVoiceMode.current ?: return
    VoiceStatusStrip(voice, Modifier.widthIn(max = ChatColumnMaxWidth))
}
