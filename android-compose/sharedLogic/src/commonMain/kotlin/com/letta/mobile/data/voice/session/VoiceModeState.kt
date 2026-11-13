package com.letta.mobile.data.voice.session

import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.loop.VoiceLoopState

/** What is still missing before voice mode can start. */
enum class VoiceSetupGap {
    /** No speech endpoint is configured. */
    NO_ENDPOINT,

    /** The configured address is not a plain http(s) base URL. */
    INVALID_ENDPOINT,

    /** The person has not agreed to send audio and text to that endpoint. */
    NO_CONSENT,
}

/** What the chat bar shows for voice mode. Carries no audio, transcript or address. */
sealed interface VoiceModeState {
    /** Whether voice mode holds the microphone or the speaker right now (pressing again stops it). */
    val active: Boolean get() = false

    /** Not running. */
    data object Off : VoiceModeState

    /** Pressing the button opens the Voice settings instead of starting: [gap] is missing. */
    data class NeedsSetup(
        /** What is missing. */
        val gap: VoiceSetupGap,
    ) : VoiceModeState

    /** The microphone permission was refused (or withdrawn); nothing is recording. */
    data object NeedsMicPermission : VoiceModeState

    /** There is no conversation to talk in (none open, or it cannot send right now). */
    data object NeedsConversation : VoiceModeState

    /** The microphone is open and waiting for the person to speak. */
    data object Listening : VoiceModeState {
        override val active: Boolean get() = true
    }

    /** The person is speaking, or what they said is being transcribed. */
    data object Hearing : VoiceModeState {
        override val active: Boolean get() = true
    }

    /** The transcript was sent as a chat message; waiting for the agent's first words. */
    data object WaitingForReply : VoiceModeState {
        override val active: Boolean get() = true
    }

    /** The agent's reply is being spoken; speaking over it interrupts it. */
    data object Speaking : VoiceModeState {
        override val active: Boolean get() = true
    }

    /** Voice mode stopped on [code]; its [VoiceErrorCode.action] says what to offer. */
    data class Error(
        /** What went wrong. */
        val code: VoiceErrorCode,
    ) : VoiceModeState
}

/** Projects the loop's state into what the chat bar shows. */
internal object VoiceModeProjection {
    /** The state for [loop], with [gap] the setup's current gap (it tells a refused grant from a refused microphone). */
    fun of(loop: VoiceLoopState, gap: VoiceSetupGap?): VoiceModeState = when (loop.phase) {
        // A session exists, so an idle loop is one whose first activation is still queued.
        VoicePhase.IDLE -> VoiceModeState.Listening
        VoicePhase.LISTENING -> if (loop.userSpeaking) VoiceModeState.Hearing else VoiceModeState.Listening
        VoicePhase.THINKING -> VoiceModeState.WaitingForReply
        VoicePhase.SPEAKING -> VoiceModeState.Speaking
        VoicePhase.FAILED -> failure(loop.failure, gap)
    }

    /**
     * A permission failure is a missing destination grant when the setup now has a gap, otherwise the
     * microphone: the ear reports both with the same code.
     */
    private fun failure(error: VoiceError?, gap: VoiceSetupGap?): VoiceModeState = when {
        error == null -> VoiceModeState.Error(VoiceErrorCode.ENGINE_FAILED)
        error.code != VoiceErrorCode.PERMISSION_REQUIRED -> VoiceModeState.Error(error.code)
        gap != null -> VoiceModeState.NeedsSetup(gap)
        else -> VoiceModeState.NeedsMicPermission
    }
}
