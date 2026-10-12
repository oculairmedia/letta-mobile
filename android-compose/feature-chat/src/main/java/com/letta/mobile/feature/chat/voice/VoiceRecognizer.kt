package com.letta.mobile.feature.chat.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject

/**
 * letta-mobile-7a1vl: what one recognition session reports back. Kept free of Android types so the
 * view model's session logic is unit-testable with a fake [VoiceRecognizerFactory].
 */
internal interface VoiceRecognitionEvents {
    fun onRms(rmsdB: Float)
    fun onPartialText(text: String)
    fun onFinalText(text: String)
    fun onError(error: Int)
}

/** One recognition session: a fresh recognizer that is torn down for good by [release]. */
internal interface VoiceRecognizerSession {
    fun start()
    fun stop()

    /** Stops, cancels and destroys the recognizer and detaches its listener. Idempotent. */
    fun release()
}

internal fun interface VoiceRecognizerFactory {
    fun create(events: VoiceRecognitionEvents): VoiceRecognizerSession
}

internal class AndroidVoiceRecognizerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) : VoiceRecognizerFactory {
    override fun create(events: VoiceRecognitionEvents): VoiceRecognizerSession =
        AndroidVoiceRecognizerSession(
            recognizer = SpeechRecognizer.createSpeechRecognizer(context),
            listener = DetachableRecognitionListener(events),
            intent = recognizerIntent(),
        )

    private fun recognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // letta-mobile-71sh: respect device locale rather than hard-coding en-US.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    }
}

private class AndroidVoiceRecognizerSession(
    recognizer: SpeechRecognizer,
    private val listener: DetachableRecognitionListener,
    private val intent: Intent,
) : VoiceRecognizerSession {
    private var recognizer: SpeechRecognizer? = recognizer.apply { setRecognitionListener(listener) }

    override fun start() {
        recognizer?.startListening(intent)
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun release() {
        listener.detach()
        val current = recognizer ?: return
        recognizer = null
        current.stopListening()
        current.cancel()
        current.destroy()
    }
}

/**
 * letta-mobile-7a1vl: the framework wraps this in a binder stub
 * (`SpeechRecognizerImpl$InternalRecognitionListener`) that native code keeps alive until the
 * recognition service's side is collected, well past [SpeechRecognizer.destroy]. So the listener
 * is a top-level class whose only reference out is [events], cleared by [detach]: a retained stub
 * pins this empty shell, never the view model or the chat page.
 */
internal class DetachableRecognitionListener(events: VoiceRecognitionEvents) : RecognitionListener {
    @Volatile
    private var events: VoiceRecognitionEvents? = events

    val isDetached: Boolean get() = events == null

    fun detach() {
        events = null
    }

    override fun onReadyForSpeech(params: Bundle?) = Unit

    override fun onBeginningOfSpeech() = Unit

    override fun onRmsChanged(rmsdB: Float) {
        events?.onRms(rmsdB)
    }

    override fun onBufferReceived(buffer: ByteArray?) = Unit

    override fun onEndOfSpeech() = Unit

    override fun onError(error: Int) {
        events?.onError(error)
    }

    override fun onResults(results: Bundle?) {
        events?.onFinalText(firstMatch(results))
    }

    override fun onPartialResults(partialResults: Bundle?) {
        events?.onPartialText(firstMatch(partialResults))
    }

    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun firstMatch(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}

@Module
@InstallIn(ViewModelComponent::class)
internal abstract class VoiceRecognizerModule {
    @Binds
    abstract fun bindVoiceRecognizerFactory(impl: AndroidVoiceRecognizerFactory): VoiceRecognizerFactory
}
