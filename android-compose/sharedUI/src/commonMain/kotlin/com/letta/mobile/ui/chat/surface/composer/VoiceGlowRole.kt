package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.letta.mobile.data.voice.session.VoiceModeState
import com.letta.mobile.ui.chat.voice.VoiceModeHandle
import com.letta.mobile.ui.theme.customColors

/** Who voice mode's glow and the prompt's outline speak for, in each state. */
internal enum class VoiceGlowRole {
    /** The person: listening, or hearing them. Drawn in [voiceYouColor]. */
    You,

    /** The agent: thinking about the reply, or speaking it. Drawn in [voiceAgentColor]. */
    Agent,

    /** The last utterance or voice mode failed: the outline holds still in the error colour. */
    Error,

    /** Nothing to show: voice mode is off, or waits on setup, permission or a conversation. */
    Idle,
}

/** The role for [state]. */
internal fun voiceGlowRole(state: VoiceModeState): VoiceGlowRole = when (state) {
    VoiceModeState.Listening, VoiceModeState.Hearing -> VoiceGlowRole.You
    VoiceModeState.WaitingForReply, VoiceModeState.Speaking -> VoiceGlowRole.Agent
    is VoiceModeState.TurnFailed, is VoiceModeState.Error -> VoiceGlowRole.Error
    else -> VoiceGlowRole.Idle
}

/**
 * The level the glow and the outline follow in [state] (read at draw time): the microphone while
 * listening and hearing, the reply's playback while speaking; null where they hold steady (the
 * thinking animation, a failure, nothing shown).
 */
internal fun VoiceModeHandle.glowLevel(state: VoiceModeState): (() -> Float)? = when (state) {
    VoiceModeState.Listening, VoiceModeState.Hearing -> { { microphoneLevel.value } }
    VoiceModeState.Speaking -> { { playbackLevel.value } }
    else -> null
}

/**
 * The person's colour: the theme's amber accent (customColors.runningColor), the hue furthest from
 * the teal and cyan the agent's thinking glow is drawn in, in both themes.
 */
@Composable
internal fun voiceYouColor(): Color = MaterialTheme.customColors.runningColor

/** The agent's colour: the thinking glow's own (the tertiary accent the chat's glows run in). */
@Composable
internal fun voiceAgentColor(): Color = MaterialTheme.colorScheme.tertiary

/**
 * The person's colour for a thin edge on the current surface: on a light surface the amber is
 * deepened toward its own "on" colour so a hairline of it still stands out; on a dark one it is used
 * as it is.
 */
@Composable
internal fun voiceYouEdgeColor(): Color {
    val you = voiceYouColor()
    val lightSurface = MaterialTheme.colorScheme.surface.luminance() > LIGHT_SURFACE_LUMINANCE
    return if (lightSurface) lerp(you, MaterialTheme.customColors.onRunningColor, LIGHT_EDGE_DEEPEN) else you
}

private const val LIGHT_SURFACE_LUMINANCE = 0.5f
private const val LIGHT_EDGE_DEEPEN = 0.45f
