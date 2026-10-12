package com.letta.mobile.feature.chat.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

private const val AUDIO_METER_MIN_DB = -2.0f
private const val AUDIO_METER_MAX_DB = 100.0f
private val STOP_GRACE = 500.milliseconds

/**
 * @param recognizing      true while [android.speech.SpeechRecognizer] is actively listening.
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

/**
 * Hold-to-dictate state machine, shared by the dictation button and the recognizer overlay through
 * the chat screen's ViewModelStoreOwner.
 *
 * letta-mobile-7a1vl (leak): this view model holds nothing from the screen. Final transcripts go
 * out on [dictations], which the dictation button collects only while it is composed, and every
 * session gets its own recognizer and listener, destroyed and detached as soon as the session
 * ends (result, error, cancel, or [onCleared]). A binder stub the framework keeps alive can then
 * only pin a detached listener shell.
 */
@HiltViewModel
internal class VoiceInputViewModel @Inject constructor(
    private val recognizerFactory: VoiceRecognizerFactory,
) : ViewModel() {
    private val _uiState = MutableStateFlow(VoiceInputUiState())
    val uiState = _uiState.asStateFlow()

    private val _dictations = MutableSharedFlow<String>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Non-blank final transcripts; nothing is replayed to a collector that arrives late. */
    val dictations: SharedFlow<String> = _dictations.asSharedFlow()

    private var session: Session? = null
    private var generation = 0

    fun startSpeechRecognition() {
        endSession()
        generation++
        val events = Session.Events(this)
        val recognizer = recognizerFactory.create(events)
        val started = Session(events, recognizer)
        session = started
        events.session = started
        recognizer.start()
        _uiState.update { it.copy(recognizing = true, recognizedText = "", amplitude = 0) }
    }

    fun stopSpeechRecognition() {
        val stopping = generation
        viewModelScope.launch {
            delay(STOP_GRACE)
            // A newer press started its own session in the meantime: leave it alone.
            if (stopping != generation) return@launch
            session?.recognizer?.stop()
            _uiState.update { it.copy(recognizing = false, amplitude = 0) }
        }
    }

    fun cancelSpeechRecognition() {
        endSession()
        _uiState.update { it.copy(recognizing = false, amplitude = 0, recognizedText = "") }
    }

    override fun onCleared() {
        endSession()
    }

    private fun onRms(from: Session, rmsdB: Float) {
        if (from !== session) return
        _uiState.update { it.copy(amplitude = convertRmsDbToAmplitude(rmsdB)) }
    }

    private fun onPartialText(from: Session, text: String) {
        if (from !== session) return
        _uiState.update { it.copy(recognizedText = text) }
    }

    private fun onFinalText(from: Session, text: String) {
        if (from !== session) return
        endSession()
        _uiState.update { it.copy(recognizedText = text, recognizing = false, amplitude = 0) }
        if (text.isNotBlank()) _dictations.tryEmit(text)
    }

    private fun onError(from: Session) {
        if (from !== session) return
        // The recognizer is dead; release it now. The UI flips back when the hold ends, as before.
        endSession()
    }

    private fun endSession() {
        val ending = session ?: return
        session = null
        ending.events.detach()
        ending.recognizer?.release()
        ending.recognizer = null
    }

    private fun convertRmsDbToAmplitude(rmsdB: Float): Int {
        val clampedRmsdB = max(rmsdB, AUDIO_METER_MIN_DB).coerceAtMost(AUDIO_METER_MAX_DB)
        return ((clampedRmsdB - AUDIO_METER_MIN_DB) * 65535f / (AUDIO_METER_MAX_DB - AUDIO_METER_MIN_DB)).toInt()
    }

    private class Session(val events: Events, var recognizer: VoiceRecognizerSession?) {
        /** Forwards to the view model until [detach]; a late callback after that goes nowhere. */
        class Events(owner: VoiceInputViewModel) : VoiceRecognitionEvents {
            @Volatile
            private var owner: VoiceInputViewModel? = owner
            var session: Session? = null

            fun detach() {
                owner = null
                session = null
            }

            override fun onRms(rmsdB: Float) {
                val s = session ?: return
                owner?.onRms(s, rmsdB)
            }

            override fun onPartialText(text: String) {
                val s = session ?: return
                owner?.onPartialText(s, text)
            }

            override fun onFinalText(text: String) {
                val s = session ?: return
                owner?.onFinalText(s, text)
            }

            override fun onError(error: Int) {
                val s = session ?: return
                owner?.onError(s)
            }
        }
    }
}
