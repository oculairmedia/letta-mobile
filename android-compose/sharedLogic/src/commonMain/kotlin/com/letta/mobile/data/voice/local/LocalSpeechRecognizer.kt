package com.letta.mobile.data.voice.local

import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceText
import kotlinx.coroutines.flow.Flow

/**
 * The device's own speech-to-text, as voice mode uses it: a port each platform implements over its
 * recogniser (Android's `SpeechRecognizer`, a browser's speech API, an operating system's
 * recogniser). It carries no platform types and makes no decisions: when to listen, restart, pause
 * for the app's own speech or hand over to the server is [LocalVoiceEar]'s.
 *
 * Implementations own their threading: every call may come from any thread, and a recogniser that
 * must be driven from one thread hops there itself.
 */
interface LocalSpeechRecognizer {
    /**
     * Whether the platform removes the app's own output from what the recogniser hears. Only then
     * does the ear keep listening while the app speaks; otherwise it pauses (half-duplex).
     */
    val echoCancelled: Boolean get() = false

    /** Whether, and how, this device can recognise speech in [language] (a BCP 47 tag; null: the device's language). */
    fun availability(language: String?): LocalSpeechAvailability

    /** Prepares the recogniser for [request]; nothing listens until a recognition is collected. */
    fun open(request: LocalRecognitionRequest): LocalRecognizerSession
}

/** Whether a device can recognise speech locally. */
enum class LocalSpeechAvailability {
    /** A recogniser that runs on the device itself. */
    ON_DEVICE,

    /** The platform's recognition service; it prefers offline recognition but may use the network. */
    PLATFORM_SERVICE,

    /** No recogniser for this language on this device. */
    UNAVAILABLE,
    ;

    /** Whether there is a recogniser to use. */
    val available: Boolean get() = this != UNAVAILABLE
}

/** What a recogniser is opened for. */
data class LocalRecognitionRequest(
    /** The language to recognise, as a BCP 47 tag; null uses the device's language. */
    val language: String? = null,
    /** Prefer a recogniser that runs on the device over the platform's service. */
    val preferOnDevice: Boolean = true,
)

/**
 * An opened recogniser, reused for one recognition after another. [close] releases it; a
 * recognition still running is cancelled.
 */
interface LocalRecognizerSession : AutoCloseable {
    /** One utterance's recognition. */
    fun recognize(): LocalRecognition

    /** Releases the recogniser. Idempotent. */
    override fun close()
}

/** One utterance being recognised. */
interface LocalRecognition {
    /**
     * Collecting starts listening; the flow completes after its [LocalRecognitionEvent.Final] or
     * [LocalRecognitionEvent.Failed]. Cancelling the collection cancels the recognition, and the
     * collection returns only once the recogniser has stopped listening. Collect at most once.
     */
    val events: Flow<LocalRecognitionEvent>

    /** The person is done: stop listening and deliver what was heard as the final result. */
    fun finish()
}

/** What a recognition reports. Text values are the person's words: never log or report them. */
sealed interface LocalRecognitionEvent {
    /** The recogniser is listening. */
    data object Ready : LocalRecognitionEvent

    /** The input [level], for a meter. */
    data class Level(
        /** The current loudness. */
        val level: AudioLevel,
    ) : LocalRecognitionEvent

    /** The words recognised so far; each replaces the last. */
    data class Partial(
        /** The words so far. */
        val text: VoiceText,
    ) : LocalRecognitionEvent

    /** The recogniser decided the person stopped speaking; the final result follows. */
    data object EndOfSpeech : LocalRecognitionEvent

    /** The recognised utterance (possibly empty); the recognition is over. */
    data class Final(
        /** The words. */
        val text: VoiceText,
    ) : LocalRecognitionEvent

    /** The recognition ended without a result; [error] says why. */
    data class Failed(
        /** Why. */
        val error: LocalRecognitionError,
    ) : LocalRecognitionEvent
}

/** Why a recognition ended without a result, in platform-neutral terms. */
enum class LocalRecognitionError {
    /** Nothing was recognised: a normal outcome of silence. */
    NO_MATCH,

    /** No speech was heard before the recogniser gave up: a normal outcome of silence. */
    SPEECH_TIMEOUT,

    /** The recogniser is busy with another request. */
    BUSY,

    /** The recogniser refused more requests for now. */
    TOO_MANY_REQUESTS,

    /** A network problem, for a recogniser that uses one. */
    NETWORK,

    /** The recognition service failed or went away. */
    SERVICE,

    /** The audio could not be recorded. */
    AUDIO,

    /** The recogniser does not support the language. */
    LANGUAGE_UNSUPPORTED,

    /** The language is supported but not available now (its offline model is missing). */
    LANGUAGE_UNAVAILABLE,

    /** The app may not record. */
    PERMISSION,

    /** Any other failure. */
    OTHER,
}

/** No recogniser: what a platform without one amounts to. Opening it is never needed, since it is never available. */
internal object UnavailableRecognizer : LocalSpeechRecognizer {
    override fun availability(language: String?): LocalSpeechAvailability = LocalSpeechAvailability.UNAVAILABLE

    override fun open(request: LocalRecognitionRequest): LocalRecognizerSession =
        throw UnsupportedOperationException("No local speech recogniser on this platform")
}
