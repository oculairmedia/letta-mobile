package com.letta.mobile.data.voice.activation

import com.letta.mobile.data.voice.wake.WakeWordEvent
import com.letta.mobile.data.voice.wake.WakeWordListener
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull

/**
 * The wake word as an activation source: an accepted detection from [listener] activates the loop;
 * an unavailable listener (no engine, missing assets, a failed engine) degrades to a notice and the
 * loop keeps working with push-to-talk and tap. Collecting [events] starts the listener; cancelling
 * stops it and releases the microphone.
 */
class WakeWordActivationSource(private val listener: WakeWordListener) : VoiceActivationSource {
    override val events: Flow<VoiceActivationEvent> = listener.events.mapNotNull(::toActivation)

    private fun toActivation(event: WakeWordEvent): VoiceActivationEvent? = when (event) {
        is WakeWordEvent.Detected -> VoiceActivationEvent.Activated(VoiceActivationKind.WAKE_WORD)
        is WakeWordEvent.Unavailable -> VoiceActivationEvent.Degraded(VoiceActivationKind.WAKE_WORD, event.error)
        else -> null
    }
}
