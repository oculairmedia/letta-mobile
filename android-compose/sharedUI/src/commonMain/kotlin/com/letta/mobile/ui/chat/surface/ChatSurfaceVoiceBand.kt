package com.letta.mobile.ui.chat.surface

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.letta.mobile.ui.ambient.AmbientMotionStatus
import com.letta.mobile.ui.chat.surface.ambient.AmbientGlowPlacement
import com.letta.mobile.ui.chat.surface.ambient.ChatAmbient
import com.letta.mobile.ui.chat.surface.ambient.ChatPanelAmbientGlow
import com.letta.mobile.ui.chat.surface.ambient.VoiceWave
import com.letta.mobile.ui.chat.surface.composer.VoiceBandVisual

/** The thinking glow's status while voice mode waits for the reply: the agent is working. */
private val VoiceWaitingAmbient = ChatAmbient(AmbientMotionStatus.Running)

/**
 * The chat page's backdrops for voice mode's band ([com.letta.mobile.ui.chat.surface.composer.LocalVoiceBandBackdrop]):
 * the reactive wave on the band's surface, the chat's own thinking glow while waiting, and nothing
 * for the still states. Both shaders are the page's, compiled once.
 */
internal val ChatVoiceBandBackdrop: @Composable (VoiceBandVisual, () -> Float, Modifier) -> Unit = { layer, level, modifier ->
    when (layer) {
        VoiceBandVisual.MicrophoneWave, VoiceBandVisual.PlaybackWave ->
            VoiceWave(level = level, background = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier)
        VoiceBandVisual.Thinking -> ChatPanelAmbientGlow(VoiceWaitingAmbient, AmbientGlowPlacement.VoiceBand, modifier)
        VoiceBandVisual.Still -> Unit
    }
}
