package com.letta.mobile.ui.chat.voice

import androidx.compose.runtime.Stable
import com.letta.mobile.data.voice.endpoint.SpeechSpeed
import com.letta.mobile.data.voice.output.SpokenSegment
import com.letta.mobile.data.voice.output.VoiceSessionControls
import com.letta.mobile.data.voice.session.VoiceModeController
import kotlinx.coroutines.flow.StateFlow

/** A switch the voice card offers (mute the microphone, mute the speaker): whether it is [on], and how to [set] it. */
@Stable
class VoiceCardSwitch(val on: StateFlow<Boolean>, val set: (Boolean) -> Unit)

/** The reply's speaking speed the voice card offers: the current speed, the [choices], and how to [set] one. */
@Stable
class VoiceCardSpeed(
    val value: StateFlow<SpeechSpeed>,
    val set: (SpeechSpeed) -> Unit,
    val choices: List<SpeechSpeed> = DEFAULT_CHOICES,
) {
    /** The speeds offered unless a controller says otherwise. */
    companion object {
        val DEFAULT_CHOICES: List<SpeechSpeed> = listOf(0.75f, 1f, 1.25f, 1.5f).map(SpeechSpeed::coerce)
    }
}

/**
 * What the voice card reads beyond voice mode's state: the reply's pieces as they start playing (so
 * the spoken words can be lit and the rest dimmed), the controls it offers, and the clock the pieces'
 * times are on. A control is null where it is not offered, and the card then leaves its button out.
 * The controller's controls back it ([controllerVoiceCardFeed]); tests drive a fake.
 */
@Stable
interface VoiceCardFeed {
    /** The current reply's pieces that have started playing, in order; empty between replies. */
    val spokenSegments: StateFlow<List<SpokenSegment>>

    /** Mutes the microphone (voice mode keeps going, hearing nothing), or null. */
    val microphoneMute: VoiceCardSwitch?

    /** Mutes the reply's audio (the words still light up), or null. */
    val speakerMute: VoiceCardSwitch?

    /** The reply's speaking speed, or null. */
    val speed: VoiceCardSpeed?

    /** The clock the segments' times are on, in milliseconds; the card's turn timer reads it too. */
    fun now(): Long
}

/** The voice card's feed over [controller]'s live controls: its spoken progress, mutes, speed and clock. */
internal fun controllerVoiceCardFeed(controller: VoiceModeController): VoiceCardFeed = ControlsVoiceCardFeed(controller.controls)

private class ControlsVoiceCardFeed(private val controls: VoiceSessionControls) : VoiceCardFeed {
    override val spokenSegments: StateFlow<List<SpokenSegment>> = controls.spokenProgress
    override val microphoneMute: VoiceCardSwitch = VoiceCardSwitch(controls.microphoneMuted, controls::setMicrophoneMuted)
    override val speakerMute: VoiceCardSwitch = VoiceCardSwitch(controls.speakerMuted, controls::setSpeakerMuted)
    override val speed: VoiceCardSpeed = VoiceCardSpeed(controls.speechSpeed, controls::setSpeechSpeed)

    override fun now(): Long = controls.nowMs()
}
