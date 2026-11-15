package com.letta.mobile.feature.chat.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.letta.mobile.data.voice.local.LocalRecognition
import com.letta.mobile.data.voice.local.LocalRecognitionEvent
import com.letta.mobile.data.voice.local.LocalRecognitionRequest
import com.letta.mobile.data.voice.local.LocalRecognizerSession
import com.letta.mobile.data.voice.local.LocalSpeechRecognizer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val AMPLITUDE_MAX = 65535f
private val STOP_GRACE = 500.milliseconds

/** Dictation keeps the platform recogniser it always used rather than insisting on the on-device one. */
private val DICTATION = LocalRecognitionRequest(preferOnDevice = false)

/**
 * @param recognizing      true while the recogniser is actively listening.
 * @param recognizedText   Latest (partial or final) transcription. Empty
 *                         outside a recognition session.
 * @param amplitude        Latest 0..65535 input level. AudioAnimation
 *                         normalizes this internally.
 */
internal data class VoiceInputUiState(
    val recognizing: Boolean = false,
    val recognizedText: String = "",
    val amplitude: Int = 0,
)

/**
 * Hold-to-dictate state machine, shared by the dictation button and the recognizer overlay through
 * the chat screen's ViewModelStoreOwner, over the shared [LocalSpeechRecognizer] port.
 *
 * This view model holds nothing from the screen: final transcripts go out on [dictations], which
 * the dictation button collects only while it is composed, and every session opens its own
 * recogniser and closes it as soon as the session ends (result, error, cancel, or [onCleared]);
 * the binding detaches its framework listener when the recognition's collection ends, so a binder
 * stub the framework keeps alive can only pin an empty shell.
 */
@HiltViewModel
internal class VoiceInputViewModel @Inject constructor(
    private val recognizer: LocalSpeechRecognizer,
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
        val opened = recognizer.open(DICTATION)
        val started = Session(opened, opened.recognize())
        session = started
        started.job = viewModelScope.launch { started.recognition.events.collect { on(started, it) } }
        _uiState.update { it.copy(recognizing = true, recognizedText = "", amplitude = 0) }
    }

    fun stopSpeechRecognition() {
        val stopping = generation
        viewModelScope.launch {
            delay(STOP_GRACE)
            // A newer press started its own session in the meantime: leave it alone.
            if (stopping != generation) return@launch
            session?.recognition?.finish()
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

    private fun on(from: Session, event: LocalRecognitionEvent) {
        if (from !== session) return
        when (event) {
            is LocalRecognitionEvent.Level -> _uiState.update { it.copy(amplitude = (event.level.value * AMPLITUDE_MAX).toInt()) }
            is LocalRecognitionEvent.Partial -> _uiState.update { it.copy(recognizedText = event.text.value) }
            is LocalRecognitionEvent.Final -> onFinalText(event.text.value)
            // The recognizer is dead; release it now. The UI flips back when the hold ends, as before.
            is LocalRecognitionEvent.Failed -> endSession()
            else -> Unit
        }
    }

    private fun onFinalText(text: String) {
        endSession()
        _uiState.update { it.copy(recognizedText = text, recognizing = false, amplitude = 0) }
        if (text.isNotBlank()) _dictations.tryEmit(text)
    }

    private fun endSession() {
        val ending = session ?: return
        session = null
        ending.job?.cancel()
        ending.opened.close()
    }

    private class Session(val opened: LocalRecognizerSession, val recognition: LocalRecognition) {
        var job: Job? = null
    }
}
