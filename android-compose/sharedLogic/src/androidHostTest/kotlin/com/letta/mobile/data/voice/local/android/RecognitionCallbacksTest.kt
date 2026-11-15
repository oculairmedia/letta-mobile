package com.letta.mobile.data.voice.local.android

import android.speech.SpeechRecognizer
import com.letta.mobile.data.voice.AudioLevel
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.local.LocalRecognitionError
import com.letta.mobile.data.voice.local.LocalRecognitionEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The binding's mapping of `SpeechRecognizer`'s callbacks and error codes onto the port. */
class RecognitionCallbacksTest {
    private val seen = mutableListOf<LocalRecognitionEvent>()
    private val listener = DetachableRecognitionListener(RecognitionCallbacks { seen += it })

    @Test
    fun callbacksBecomePortEvents() {
        listener.onReadyForSpeech(null)
        listener.onBeginningOfSpeech()
        listener.onRmsChanged(RMS_SILENT)
        listener.onPartialResults(null)
        listener.onEndOfSpeech()
        listener.onResults(null)

        assertEquals(
            listOf(
                LocalRecognitionEvent.Ready,
                LocalRecognitionEvent.Level(AudioLevel.Silent),
                LocalRecognitionEvent.EndOfSpeech,
                LocalRecognitionEvent.Final(VoiceText.Empty),
            ),
            seen,
            "the recogniser's own start of speech and an empty partial report nothing; no result is an empty final",
        )
    }

    @Test
    fun partialAndFinalTextPassThrough() {
        val callbacks = RecognitionCallbacks { seen += it }
        callbacks.partial("hello")
        callbacks.partial("  ")
        callbacks.results("hello there")

        assertEquals(listOf(LocalRecognitionEvent.Partial(VoiceText("hello")), LocalRecognitionEvent.Final(VoiceText("hello there"))), seen)
    }

    @Test
    fun errorCodesMapOntoThePort() {
        val expected = mapOf(
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
            SpeechRecognizer.ERROR_CLIENT to LocalRecognitionError.OTHER,
            UNKNOWN_CODE to LocalRecognitionError.OTHER,
        )
        expected.forEach { (code, mapped) -> assertEquals<LocalRecognitionError>(mapped, localErrorOf(code), "code $code") }
        listener.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(listOf<LocalRecognitionEvent>(LocalRecognitionEvent.Failed(LocalRecognitionError.NO_MATCH)), seen)
    }

    @Test
    fun levelsSpanSilenceToLoudSpeech() {
        assertEquals(0f, levelOf(RMS_SILENT).value)
        assertEquals(1f, levelOf(RMS_LOUD).value)
        assertTrue(levelOf(RMS_MIDDLE).value in 0.4f..0.6f)
    }

    @Test
    fun aDetachedListenerForwardsNothing() {
        listener.onRmsChanged(RMS_LOUD)
        listener.detach()
        listener.onRmsChanged(RMS_LOUD)
        listener.onError(SpeechRecognizer.ERROR_AUDIO)
        listener.onResults(null)

        assertTrue(listener.isDetached)
        assertEquals(1, seen.size)
    }

    @Test
    fun theLanguageIsTheRequestsElseTheAppsElseEnglish() {
        assertEquals("fr-CA", languageTag("fr-CA"))
        assertTrue(languageTag(null).isNotBlank())
        assertTrue(languageTag(" ").isNotBlank())
    }

    private companion object {
        const val RMS_SILENT = -2f
        const val RMS_MIDDLE = 4f
        const val RMS_LOUD = 12f
        const val UNKNOWN_CODE = 999
    }
}
