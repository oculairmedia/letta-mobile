package com.letta.mobile.data.voice.local.android

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.letta.mobile.data.voice.local.LocalRecognition
import com.letta.mobile.data.voice.local.LocalRecognitionEvent
import com.letta.mobile.data.voice.local.LocalRecognitionRequest
import com.letta.mobile.data.voice.local.LocalRecognizerSession
import com.letta.mobile.data.voice.local.LocalSpeechAvailability
import com.letta.mobile.data.voice.local.LocalSpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.util.Locale

/**
 * The [LocalSpeechRecognizer] port over Android's `SpeechRecognizer`: the on-device recogniser
 * (`createOnDeviceSpeechRecognizer`, API 31+) where the device has one, else the platform's
 * recognition service asked to prefer offline recognition; partial results on. The language is the
 * request's, else the app's current locale. `SpeechRecognizer` must be driven from the main thread
 * and calls back there: every call into it is made on the main looper, from any caller's thread.
 * Recognising needs `RECORD_AUDIO` (reported as a permission failure until granted) and, on
 * Android 11+, the app's `<queries>` entry for `android.speech.RecognitionService`.
 */
class AndroidLocalSpeechRecognizer(context: Context) : LocalSpeechRecognizer {
    private val context = context.applicationContext

    override fun availability(language: String?): LocalSpeechAvailability = when {
        onDeviceAvailable() -> LocalSpeechAvailability.ON_DEVICE
        SpeechRecognizer.isRecognitionAvailable(context) -> LocalSpeechAvailability.PLATFORM_SERVICE
        else -> LocalSpeechAvailability.UNAVAILABLE
    }

    override fun open(request: LocalRecognitionRequest): LocalRecognizerSession =
        AndroidRecognizerSession(context, request, onDevice = request.preferOnDevice && onDeviceAvailable())

    private fun onDeviceAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
}

/** One `SpeechRecognizer`, created on first use on the main thread and reused across recognitions. */
private class AndroidRecognizerSession(
    private val context: Context,
    request: LocalRecognitionRequest,
    private val onDevice: Boolean,
) : LocalRecognizerSession {
    private val main = Handler(Looper.getMainLooper())
    private val intent = recognizerIntent(request, onDevice)

    // Touched on the main thread only.
    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var closed = false

    override fun recognize(): LocalRecognition = AndroidRecognition(this)

    /** Starts listening with [listener]; false when the session is closed. Main thread. */
    fun start(listener: DetachableRecognitionListener): Boolean {
        if (closed) return false
        val current = recognizer ?: create().also { recognizer = it }
        current.setRecognitionListener(listener)
        current.startListening(intent)
        return true
    }

    /** Cancels the recognition in progress, if any. Main thread. */
    fun cancel() {
        recognizer?.cancel()
    }

    fun finish() {
        main.post { recognizer?.stopListening() }
    }

    override fun close() {
        closed = true
        main.post {
            recognizer?.destroy()
            recognizer = null
        }
    }

    private fun create(): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
}

/** One recognition: collecting starts it on the main thread; cancelling cancels it there. */
private class AndroidRecognition(private val session: AndroidRecognizerSession) : LocalRecognition {
    override val events: Flow<LocalRecognitionEvent> = callbackFlow {
        val listener = DetachableRecognitionListener(
            RecognitionCallbacks { event ->
                trySend(event)
                if (event is LocalRecognitionEvent.Final || event is LocalRecognitionEvent.Failed) close()
            },
        )
        if (!session.start(listener)) close()
        awaitClose {
            listener.detach()
            session.cancel()
        }
    }.flowOn(Dispatchers.Main.immediate)

    override fun finish() = session.finish()
}

/** The recognizer intent: free-form, one result, partial results, the language, offline when asked. */
private fun recognizerIntent(request: LocalRecognitionRequest, onDevice: Boolean): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag(request.language))
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        if (request.preferOnDevice && !onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

/** The requested language, else the app's current locale, else US English. */
internal fun languageTag(requested: String?): String {
    val tag = requested?.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag()
    return tag.takeUnless { it.isBlank() || it == UNDETERMINED } ?: DEFAULT_LANGUAGE
}

private const val UNDETERMINED = "und"
private const val DEFAULT_LANGUAGE = "en-US"
