package com.letta.mobile.data.voice.local.android

import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.local.LocalRecognitionError
import com.letta.mobile.data.voice.local.LocalRecognitionEvent

/**
 * `SpeechRecognizer`'s callbacks, already read out of their bundles, as [LocalRecognitionEvent]s
 * for [sink]. The recogniser's own start of speech is not reported (it fires on noise).
 */
internal class RecognitionCallbacks(private val sink: (LocalRecognitionEvent) -> Unit) {
    fun ready() = sink(LocalRecognitionEvent.Ready)

    fun rms(rmsdB: Float) = sink(LocalRecognitionEvent.Level(levelOf(rmsdB)))

    fun partial(text: String?) {
        if (!text.isNullOrBlank()) sink(LocalRecognitionEvent.Partial(VoiceText(text)))
    }

    fun endOfSpeech() = sink(LocalRecognitionEvent.EndOfSpeech)

    fun results(text: String?) = sink(LocalRecognitionEvent.Final(VoiceText(text.orEmpty())))

    fun error(code: Int) = sink(LocalRecognitionEvent.Failed(localErrorOf(code)))
}

/**
 * The framework wraps a `RecognitionListener` in a binder stub that native code keeps alive until
 * the recognition service's side is collected, well past `SpeechRecognizer.destroy`. So the listener
 * is a small class whose only reference out is [callbacks], cleared by [detach]: a retained stub
 * pins this empty shell, never the recognition or whoever collects it.
 */
internal class DetachableRecognitionListener(callbacks: RecognitionCallbacks) : RecognitionListener {
    @Volatile
    private var callbacks: RecognitionCallbacks? = callbacks

    val isDetached: Boolean get() = callbacks == null

    fun detach() {
        callbacks = null
    }

    override fun onReadyForSpeech(params: Bundle?) {
        callbacks?.ready()
    }

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) {
        callbacks?.rms(rmsdB)
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() {
        callbacks?.endOfSpeech()
    }

    override fun onError(error: Int) {
        callbacks?.error(error)
    }

    override fun onResults(results: Bundle?) {
        callbacks?.results(firstMatch(results))
    }

    override fun onPartialResults(partialResults: Bundle?) {
        callbacks?.partial(firstMatch(partialResults))
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun firstMatch(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}

/** `SpeechRecognizer`'s error [code] in the port's terms; a code it does not name is OTHER. */
internal fun localErrorOf(code: Int): LocalRecognitionError = ERRORS[code] ?: LocalRecognitionError.OTHER

private val ERRORS: Map<Int, LocalRecognitionError> = mapOf(
    SpeechRecognizer.ERROR_NO_MATCH to LocalRecognitionError.NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT to LocalRecognitionError.SPEECH_TIMEOUT,
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY to LocalRecognitionError.BUSY,
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS to LocalRecognitionError.TOO_MANY_REQUESTS,
    SpeechRecognizer.ERROR_NETWORK to LocalRecognitionError.NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT to LocalRecognitionError.NETWORK,
    SpeechRecognizer.ERROR_SERVER to LocalRecognitionError.SERVICE,
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED to LocalRecognitionError.SERVICE,
    SpeechRecognizer.ERROR_AUDIO to LocalRecognitionError.AUDIO,
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED to LocalRecognitionError.LANGUAGE_UNSUPPORTED,
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE to LocalRecognitionError.LANGUAGE_UNAVAILABLE,
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS to LocalRecognitionError.PERMISSION,
)

/** `onRmsChanged` reports roughly -2 dB (silence) to 10 dB (loud speech); that span maps onto 0..1. */
internal fun levelOf(rmsdB: Float): AudioLevel = AudioLevel.clamped((rmsdB - RMS_FLOOR_DB) / (RMS_CEILING_DB - RMS_FLOOR_DB))

private const val RMS_FLOOR_DB = -2f
private const val RMS_CEILING_DB = 10f
