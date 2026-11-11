package com.letta.mobile.data.voice.wake

import com.letta.mobile.data.voice.WakeLanguage
import com.letta.mobile.data.voice.WakePhrase
import com.letta.mobile.data.voice.WakeSensitivity
import com.letta.mobile.data.voice.WakeWordEngineId
import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.SamplePosition
import com.letta.mobile.data.voice.wake.assets.ReadyWakeWordAssets
import com.letta.mobile.data.voice.wake.assets.WakeWordAsset
import kotlin.jvm.JvmInline

/** How an engine learns its phrases. */
enum class WakePhraseKind {
    /** Any phrase typed as text (converted to sounds by the engine). */
    TYPED_TEXT,

    /** A phrase enrolled by the person speaking it. */
    SPOKEN_ENROLLMENT,

    /** Only phrases from the engine's own fixed set. */
    FIXED_SET,
}

/** What an engine is and what it needs. */
data class WakeWordEngineDescriptor(
    /** The id it registers under. */
    val id: WakeWordEngineId,
    /** How it learns phrases. */
    val phraseKinds: Set<WakePhraseKind>,
    /** The languages it understands. */
    val languages: Set<WakeLanguage>,
    /** The most phrases it listens for at once. */
    val maxPhrases: Int,
    /** The exact frames it processes; nothing resamples for it. */
    val format: AudioFrameFormat,
    /** Whether its sessions implement [CalibratingWakeWordSession]. */
    val supportsCalibration: Boolean = false,
    /** The model files it needs, provisioned by a [com.letta.mobile.data.voice.wake.assets.WakeWordAssets]. */
    val requiredAssets: List<WakeWordAsset> = emptyList(),
)

/**
 * A wake-word engine: the SPI an engine adapter implements.
 *
 * Privacy and isolation contract: an engine is pure signal processing. It never opens the
 * microphone, files or the network; it receives frames from the listener and asset bytes through
 * [ReadyWakeWordAssets] handles. Audio is processed in memory, never stored and never sent anywhere;
 * only hits leave the engine.
 */
interface WakeWordEngine {
    /** What the engine is and needs. */
    val descriptor: WakeWordEngineDescriptor

    /** Opens a session on verified [assets]. Reports failures as values; never throws for them. */
    suspend fun open(assets: ReadyWakeWordAssets): WakeWordSessionResult
}

/** Why an engine could not open (or was skipped). */
enum class WakeWordOpenFailure {
    /** A required asset is not provisioned. */
    ASSET_MISSING,

    /** A provisioned asset could not be loaded. */
    ASSET_CORRUPT,

    /** The engine cannot run on this platform. */
    UNSUPPORTED_PLATFORM,

    /** Any other engine failure. */
    ENGINE_FAILED,
}

/** The result of [WakeWordEngine.open]. */
sealed interface WakeWordSessionResult {
    /** A running session. */
    data class Opened(
        /** The session. */
        val session: WakeWordSession,
    ) : WakeWordSessionResult

    /** The engine could not open. */
    data class Failed(
        /** Why. */
        val failure: WakeWordOpenFailure,
    ) : WakeWordSessionResult
}

/** Why an engine refused a phrase. */
enum class PhraseRejection {
    /** The phrase's language is not supported. */
    UNSUPPORTED_LANGUAGE,

    /** The engine cannot turn the text into sounds it can spot. */
    UNPRONOUNCEABLE,

    /** Too short to spot reliably. */
    TOO_SHORT,

    /** More phrases than the engine supports; later ones are rejected. */
    TOO_MANY,

    /** No text. */
    EMPTY,
}

/** What an engine did with one phrase. */
sealed interface PhraseOutcome {
    /** The phrase. */
    val phrase: WakePhrase

    /** The engine listens for it. */
    data class Accepted(override val phrase: WakePhrase) : PhraseOutcome

    /** The engine refused it; the other phrases are unaffected. */
    data class Rejected(
        override val phrase: WakePhrase,
        /** Why. */
        val reason: PhraseRejection,
    ) : PhraseOutcome
}

/** An engine's confidence in a hit, from 0 to 1. */
@JvmInline
value class DetectionConfidence(
    /** The confidence, within 0..1. */
    val value: Float,
)

/** One raw detection: which phrase, how sure, and where in the capture it ended. */
data class WakeWordHit(
    /** The phrase heard. */
    val phrase: WakePhrase,
    /** How sure the engine is. */
    val confidence: DetectionConfidence,
    /** Where the detection ended. */
    val position: SamplePosition,
)

/**
 * One open engine instance.
 *
 * Thread rules: a session is used from one thread at a time (the audio thread for [process]);
 * callers serialise [setPhrases], [reset] and [close] with processing.
 */
interface WakeWordSession {
    /** Replaces the phrases listened for, without reopening; one outcome per phrase, in order. */
    suspend fun setPhrases(phrases: List<WakePhrase>): List<PhraseOutcome>

    /**
     * Processes one frame and returns a hit, or null. Non-suspending and allocation-light. A frame
     * of the wrong size, a session with no accepted phrases, and any call after [close] return null.
     */
    fun process(frame: AudioFrame): WakeWordHit?

    /** Forgets buffered audio (after a gap in the capture). */
    fun reset()

    /** Releases the engine's resources. Idempotent. */
    fun close()
}

/** A recording of the person saying a phrase once, for calibration. */
class CalibrationRecording(
    /** The frames of the recording; never stored beyond calibration. */
    val frames: List<AudioFrame>,
) {
    override fun toString(): String = "CalibrationRecording(frames=${frames.size})"
}

/** Optional capability: derive a phrase's sensitivity from the person saying it a few times. */
interface CalibratingWakeWordSession : WakeWordSession {
    /** The sensitivity that catches [recordings] of [phrase], or null when they are not usable. */
    suspend fun calibrate(phrase: WakePhrase, recordings: List<CalibrationRecording>): WakeSensitivity?
}
