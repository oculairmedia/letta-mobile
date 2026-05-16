package com.letta.mobile.feature.chat.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

private const val AUDIO_METER_MIN_DB = -2.0f
private const val AUDIO_METER_MAX_DB = 100.0f

/**
 * @param recognizing      true while [SpeechRecognizer] is actively listening.
 * @param recognizedText   Latest (partial or final) transcription. Empty
 *                         outside a recognition session.
 * @param amplitude        Latest 0..65535 RMS amplitude reading.
 *                         AudioAnimation normalizes this internally
 *                         (letta-mobile-57t5).
 */
internal data class VoiceInputUiState(
    val recognizing: Boolean = false,
    val recognizedText: String = "",
    val amplitude: Int = 0,
)

@HiltViewModel
internal class VoiceInputViewModel @Inject constructor(@ApplicationContext private val context: Context) :
    ViewModel(), RecognitionListener {
    private val _uiState = MutableStateFlow(VoiceInputUiState())
    val uiState = _uiState.asStateFlow()

    private val speechRecognizer: SpeechRecognizer
    private val recognizerIntent: Intent
    private var onRecognitionDone: ((String) -> Unit)? = null

    init {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(this@VoiceInputViewModel)
        }

        recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            // letta-mobile-71sh: respect device locale rather than hard-
            // coding en-US. The Edge Gallery original was a US-English
            // sample; ours is shipping internationally.
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    fun startSpeechRecognition(onDone: (String) -> Unit) {
        onRecognitionDone = onDone

        speechRecognizer.startListening(recognizerIntent)
        _uiState.update { it.copy(recognizing = true, recognizedText = "", amplitude = 0) }
    }

    fun stopSpeechRecognition() {
        viewModelScope.launch {
            delay(500)
            speechRecognizer.stopListening()
            _uiState.update { it.copy(recognizing = false, amplitude = 0) }
        }
    }

    fun cancelSpeechRecognition() {
        speechRecognizer.cancel()
        onRecognitionDone = null
        _uiState.update { it.copy(recognizing = false, amplitude = 0, recognizedText = "") }
    }

    private fun setRecognizedText(text: String) {
        _uiState.update { it.copy(recognizedText = text) }
    }

    override fun onReadyForSpeech(params: Bundle?) {}

    override fun onBeginningOfSpeech() {}

    override fun onRmsChanged(rmsdB: Float) {
        _uiState.update { it.copy(amplitude = convertRmsDbToAmplitude(rmsdB)) }
    }

    override fun onBufferReceived(buffer: ByteArray?) {}

    override fun onEndOfSpeech() {}

    override fun onError(error: Int) {}

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            setRecognizedText(matches[0])
        } else {
            setRecognizedText("")
        }

        onRecognitionDone?.invoke(uiState.value.recognizedText)
        _uiState.update { it.copy(recognizing = false, amplitude = 0) }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        if (!matches.isNullOrEmpty()) {
            setRecognizedText(matches[0])
        } else {
            setRecognizedText("")
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}

    override fun onCleared() {
        super.onCleared()
        speechRecognizer.destroy()
    }

    private fun convertRmsDbToAmplitude(rmsdB: Float): Int {
        val clampedRmsdB = max(rmsdB, AUDIO_METER_MIN_DB).coerceAtMost(AUDIO_METER_MAX_DB)
        return ((clampedRmsdB - AUDIO_METER_MIN_DB) * 65535f / (AUDIO_METER_MAX_DB - AUDIO_METER_MIN_DB)).toInt()
    }
}
