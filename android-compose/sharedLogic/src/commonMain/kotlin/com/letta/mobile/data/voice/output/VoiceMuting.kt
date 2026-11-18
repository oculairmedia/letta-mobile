package com.letta.mobile.data.voice.output

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.audio.PcmChunk
import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * A speaker that follows [muted] while it plays: muted, the player writes silence at the audio's
 * own pace ([AudioPlayer.setMuted]), so segments still start and finish when they would have and
 * spoken progress keeps moving.
 */
internal class MutedSpeaker(private val player: AudioPlayer, private val muted: StateFlow<Boolean>) : AudioPlayer {
    override suspend fun play(chunks: Flow<PcmChunk>, onLevel: (AudioLevel) -> Unit) = coroutineScope {
        val follower = launch(start = CoroutineStart.UNDISPATCHED) { muted.collect { player.setMuted(it) } }
        try {
            player.play(chunks, onLevel)
        } finally {
            follower.cancel()
        }
    }

    override fun setMuted(muted: Boolean) = player.setMuted(muted)
}

/**
 * A microphone whose frames are silence while [muted]: nothing the person says then reaches the
 * ear, is transcribed or leaves the device. Frames keep flowing, so positions and timing hold.
 */
internal class MutedFrameSource(private val source: AudioFrameSource, private val muted: StateFlow<Boolean>) : AudioFrameSource {
    override val format: AudioFrameFormat get() = source.format

    override val echoCancelled: Boolean get() = source.echoCancelled

    override fun frames(): Flow<AudioFrame> = source.frames().map { frame ->
        if (muted.value) AudioFrame(ShortArray(frame.samples.size), frame.start) else frame
    }
}

/**
 * An ear that ignores the person while [muted]: a turn that starts while muted is dropped whole
 * (its partial words, its end and its utterance), and the input level reads silent. A turn that
 * started before the mute goes through, so the loop never waits on a turn that cannot end.
 */
internal class MutedEar(private val ear: VoiceEar, private val muted: StateFlow<Boolean>) : VoiceEar by ear {
    override fun listen(): Flow<VoiceEarEvent> = flow {
        val filter = TurnFilter()
        ear.listen().collect { event -> filter.pass(event)?.let { emit(it) } }
    }

    /** Decides, event by event, what of one listen goes through. */
    private inner class TurnFilter {
        private var dropping = false

        /** What to emit for [event], or null to drop it. */
        fun pass(event: VoiceEarEvent): VoiceEarEvent? {
            if (event == VoiceEarEvent.SpeechStarted) dropping = muted.value
            if (event is VoiceEarEvent.InputLevel) return if (muted.value) VoiceEarEvent.InputLevel(AudioLevel.Silent) else event
            val suppressed = dropping && event.belongsToTurn()
            if (event.endsTurn()) dropping = false
            return event.takeUnless { suppressed }
        }
    }

    private fun VoiceEarEvent.endsTurn(): Boolean = this is VoiceEarEvent.EndOfTurn || this is VoiceEarEvent.Failed

    private fun VoiceEarEvent.belongsToTurn(): Boolean = when (this) {
        VoiceEarEvent.SpeechStarted, VoiceEarEvent.SpeechEnded, is VoiceEarEvent.PartialTranscript, is VoiceEarEvent.EndOfTurn -> true
        else -> false
    }
}
