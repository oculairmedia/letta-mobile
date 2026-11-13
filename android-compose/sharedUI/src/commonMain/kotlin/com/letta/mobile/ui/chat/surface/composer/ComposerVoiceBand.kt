package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.unit.Dp
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.ui.chat.surface.touchStyle
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.theme.ChatComposerDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion

/** What voice mode's band draws behind its status line. */
internal enum class VoiceBandVisual {
    /** The reactive wave following the microphone: listening, and hearing the person. */
    MicrophoneWave,

    /** The reactive wave following the reply as it plays: speaking. */
    PlaybackWave,

    /** The chat's thinking glow: waiting for the reply. */
    Thinking,

    /** Nothing moving, the band's muted surface: a failure, a missing setup, permission or conversation. */
    Still,
    ;

    /** The layer it draws on: both waves share one, so listening to speaking changes the level, not the layer. */
    val layer: VoiceBandVisual get() = if (this == PlaybackWave) MicrophoneWave else this
}

/** The band's visual for [state]. */
internal fun voiceBandVisual(state: VoiceModeState): VoiceBandVisual = when (state) {
    VoiceModeState.Listening, VoiceModeState.Hearing -> VoiceBandVisual.MicrophoneWave
    VoiceModeState.WaitingForReply -> VoiceBandVisual.Thinking
    VoiceModeState.Speaking -> VoiceBandVisual.PlaybackWave
    else -> VoiceBandVisual.Still
}

/**
 * Draws a band layer ([VoiceBandVisual.MicrophoneWave], [VoiceBandVisual.Thinking] or
 * [VoiceBandVisual.Still]) filling the modifier's box, the wave following the level it is handed.
 * The chat page provides it with its shaders; without one the band shows its surface only.
 */
internal val LocalVoiceBandBackdrop =
    staticCompositionLocalOf<(@Composable (layer: VoiceBandVisual, level: () -> Float, modifier: Modifier) -> Unit)?> { null }

/** Which visual the band shows (tests read it; a shader's pixels cannot be asserted). */
internal val VoiceBandVisualKey = SemanticsPropertyKey<VoiceBandVisual>("VoiceBandVisual")
internal var SemanticsPropertyReceiver.voiceBandVisual by VoiceBandVisualKey

/** Whether the band moves (false under reduced motion and in the still states). */
internal val VoiceBandAnimatedKey = SemanticsPropertyKey<Boolean>("VoiceBandAnimated")
internal var SemanticsPropertyReceiver.voiceBandAnimated by VoiceBandAnimatedKey

/** The band's fixed height for the layout's input idiom. */
@Composable
internal fun voiceBandHeight(): Dp = if (touchStyle()) ChatComposerDimens.voiceBandTouch else ChatComposerDimens.voiceBandPointer

/**
 * The band's backdrop for [visual], cross-fading between layers over the standard enter duration
 * (at once under reduced motion). The wave reads the microphone level, or the playback level while
 * the reply is spoken, every frame: a level change recomposes nothing.
 */
@Composable
internal fun VoiceBandBackdrop(visual: VoiceBandVisual, handle: VoiceModeHandle, modifier: Modifier = Modifier) {
    val draw = LocalVoiceBandBackdrop.current ?: return
    val reducedMotion = LocalReducedMotion.current
    val current by rememberUpdatedState(visual)
    val level = remember(handle) {
        { if (current == VoiceBandVisual.PlaybackWave) handle.playbackLevel.value else handle.microphoneLevel.value }
    }
    Crossfade(
        targetState = visual.layer,
        modifier = modifier,
        animationSpec = if (reducedMotion) snap() else tween(LettaMotionTokens.ENTER_MILLIS),
        label = "voiceBandBackdrop",
    ) { layer ->
        draw(layer, level, Modifier.fillMaxSize())
    }
}
