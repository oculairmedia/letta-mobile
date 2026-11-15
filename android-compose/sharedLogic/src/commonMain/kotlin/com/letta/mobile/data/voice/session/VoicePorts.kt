package com.letta.mobile.data.voice.session

import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.AudioPlayer
import com.letta.mobile.data.voice.responder.VoiceChatSession
import kotlinx.coroutines.flow.Flow

/**
 * The platform's microphone and speaker as voice mode uses them. Implementations only bind the
 * operating system's audio; every decision about when to open or release them is the controller's.
 */
interface VoiceAudio {
    /** A microphone frame source producing exactly [format]; nothing is opened until it is collected. */
    fun microphone(format: AudioFrameFormat): AudioFrameSource

    /** Emits when audio devices are added or removed, so a lost microphone is reopened promptly. */
    fun deviceChanges(): Flow<Unit>

    /** The speaker. It plays 16-bit PCM as it streams in. */
    fun speaker(): AudioPlayer
}

/**
 * Whether this platform's voice-mode microphone path is echo-cancelled
 * ([AudioFrameSource.echoCancelled]); creating the source opens nothing, so this is safe to read
 * before any session (the Voice settings use it).
 */
val VoiceAudio.echoCancelled: Boolean get() = microphone(AudioFrameFormat()).echoCancelled

/** Whether the app may record, asked for on demand (Android's runtime permission; always granted on desktop). */
interface MicrophoneAccess {
    /** True when recording is allowed right now. */
    fun granted(): Boolean

    /** Asks the person; [onResult] receives whether they allowed it. Never throws. */
    fun request(onResult: (Boolean) -> Unit)

    /** Platforms without a recording permission. */
    companion object {
        /** Recording is always allowed. */
        val Granted: MicrophoneAccess = object : MicrophoneAccess {
            override fun granted(): Boolean = true

            override fun request(onResult: (Boolean) -> Unit) = onResult(true)
        }
    }
}

/**
 * Opens the chat session voice mode talks through, for the conversation open right now. Called on
 * every start and retry, on the thread that pressed (the UI thread), so a host whose sender changes
 * with the selection (desktop: one per agent) is asked again. Null when nothing can be sent there now.
 */
fun interface VoiceChatSessions {
    /** The session for the current conversation, or null. */
    fun open(): VoiceChatSession?
}
