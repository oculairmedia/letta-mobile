package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.letta.mobile.data.presence.RunPhase
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
 * The person's colour: the theme's cool voice tint (customColors.voiceYouColor), deepened on a light
 * theme so a hairline of it reads on the surface.
 */
@Composable
internal fun voiceYouColor(): Color = MaterialTheme.customColors.voiceYouColor

/**
 * The agent's speaking colour: the theme's warm voice tint (customColors.voiceAgentColor), apart from
 * the teal the run's own thinking outline and glow use.
 */
@Composable
internal fun voiceAgentColor(): Color = MaterialTheme.customColors.voiceAgentColor

/**
 * The single visual treatment selected for a composer border: [Voice] is the glow that follows a
 * voice (the person's while they talk, the agent's while its reply is spoken).
 */
internal enum class ComposerOutlineMode { Idle, Voice, Working, Thinking, Error }

/**
 * Voice owns the outline while active; otherwise use this conversation's phase.
 * AWAITING_INPUT is parked on the user: its approval card supplies the attention signal.
 */
internal fun composerOutlineMode(
    voice: VoiceModeState?,
    phase: RunPhase?,
    fallbackWorking: Boolean,
): ComposerOutlineMode = when (voice?.let(::voiceGlowRole)) {
    VoiceGlowRole.Error -> ComposerOutlineMode.Error
    VoiceGlowRole.You -> ComposerOutlineMode.Voice
    // The spoken reply glows in the agent's colour at its playback level; waiting for it keeps the beam.
    VoiceGlowRole.Agent -> if (voice == VoiceModeState.Speaking) ComposerOutlineMode.Voice else ComposerOutlineMode.Working
    VoiceGlowRole.Idle, null -> when (phase) {
        RunPhase.QUEUED,
        RunPhase.REASONING -> ComposerOutlineMode.Thinking
        RunPhase.WORKING,
        RunPhase.DELEGATING,
        RunPhase.RESPONDING,
        RunPhase.INTERRUPTING -> ComposerOutlineMode.Working
        RunPhase.IDLE,
        RunPhase.AWAITING_INPUT,
        RunPhase.FAILED,
        RunPhase.DONE -> ComposerOutlineMode.Idle
        null -> if (fallbackWorking) ComposerOutlineMode.Working else ComposerOutlineMode.Idle
    }
}
