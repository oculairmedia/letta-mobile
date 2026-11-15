package com.letta.mobile.data.voice.local

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.vad.TurnDiscardReason
import com.letta.mobile.data.voice.vad.ear.EarTurnReport
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeMark

/** Everything one local listen needs from its ear. */
internal class LocalListenParts(
    val recognizer: LocalSpeechRecognizer,
    val config: LocalEarConfig,
    val memory: LocalEarMemory,
)

/** How one recognition ended. */
internal sealed interface RecognitionOutcome {
    /** A final result arrived (sent or dropped). */
    data object Heard : RecognitionOutcome

    /** The app's output started: the recognition was cancelled. */
    data object Paused : RecognitionOutcome

    /** The recognition failed with [error]; [ready] when the recogniser had started listening. */
    data class Failed(val error: LocalRecognitionError, val ready: Boolean) : RecognitionOutcome
}

/** What a failure means for the ear. */
internal enum class FailureKind {
    /** Nothing was said: listen again. */
    SILENCE,

    /** Recreate the recogniser and retry after a wait. */
    TRANSIENT,

    /** The language cannot be recognised here: hand over. */
    LANGUAGE,

    /** Recording is not allowed: report it, never hand over. */
    PERMISSION,
}

/** What [error] means for the ear. */
internal fun kindOf(error: LocalRecognitionError): FailureKind = when (error) {
    LocalRecognitionError.NO_MATCH, LocalRecognitionError.SPEECH_TIMEOUT -> FailureKind.SILENCE
    LocalRecognitionError.LANGUAGE_UNSUPPORTED, LocalRecognitionError.LANGUAGE_UNAVAILABLE -> FailureKind.LANGUAGE
    LocalRecognitionError.PERMISSION -> FailureKind.PERMISSION
    else -> FailureKind.TRANSIENT
}

/** What to do after a recognition. */
private sealed interface NextStep {
    data object Listen : NextStep

    data object Recreate : NextStep

    class Stop(val giveUp: LocalGiveUp) : NextStep
}

/**
 * One collection of [LocalVoiceEar.listen] on the local recogniser: recognitions one after another,
 * paused while the app speaks, until cancelled or the recogniser cannot go on ([run] returns why).
 */
internal class LocalListenSession(private val parts: LocalListenParts, private val out: ProducerScope<VoiceEarEvent>) {
    private val config = parts.config
    private val memory = parts.memory
    private val request = LocalRecognitionRequest(config.language)
    private var announced = false
    private var failures = 0

    suspend fun run(): LocalGiveUp {
        if (memory.closed) return LocalGiveUp(VoiceErrorCode.UNAVAILABLE, null)
        if (!parts.recognizer.availability(config.language).available) {
            return LocalGiveUp(VoiceErrorCode.UNAVAILABLE, LocalHandoverReason.UNAVAILABLE)
        }
        memory.drainCommits()
        var session = parts.recognizer.open(request)
        try {
            while (true) {
                awaitQuietOutput()
                when (val next = after(recognizeOnce(session.recognize()))) {
                    NextStep.Listen -> Unit
                    NextStep.Recreate -> {
                        session.close()
                        session = parts.recognizer.open(request)
                    }
                    is NextStep.Stop -> return next.giveUp
                }
            }
        } finally {
            session.close()
        }
    }

    /**
     * When the ear pauses for output and there was output since the last recognition, waits until
     * it has been quiet for the tail.
     */
    private suspend fun awaitQuietOutput() {
        if (!config.pauseDuringOutput || !memory.outputSeen) return
        memory.outputSeen = false
        while (true) {
            memory.output.first { !it }
            val restarted = withTimeoutOrNull(config.outputTail) { memory.output.first { it } }
            if (restarted == null) return
        }
    }

    private suspend fun after(outcome: RecognitionOutcome): NextStep = when (outcome) {
        RecognitionOutcome.Heard -> NextStep.Listen.also { failures = 0 }
        RecognitionOutcome.Paused -> NextStep.Listen
        is RecognitionOutcome.Failed -> afterFailure(outcome)
    }

    private suspend fun afterFailure(outcome: RecognitionOutcome.Failed): NextStep {
        val kind = kindOf(outcome.error)
        // A recogniser that ends before it was ready is failing, not hearing silence.
        if (kind == FailureKind.SILENCE && outcome.ready) {
            failures = 0
            config.observer.failed(outcome.error, 0)
            return NextStep.Listen
        }
        failures++
        config.observer.failed(outcome.error, failures)
        return when (kind) {
            FailureKind.LANGUAGE -> NextStep.Stop(LocalGiveUp(VoiceErrorCode.UNSUPPORTED_LOCALE, LocalHandoverReason.LANGUAGE))
            FailureKind.PERMISSION -> NextStep.Stop(LocalGiveUp(VoiceErrorCode.PERMISSION_REQUIRED, null))
            else -> retryOrStop()
        }
    }

    private suspend fun retryOrStop(): NextStep {
        val wait = config.retryDelays.getOrNull(failures - 1)
            ?: return NextStep.Stop(LocalGiveUp(VoiceErrorCode.ENGINE_FAILED, LocalHandoverReason.KEEPS_FAILING))
        delay(wait)
        return NextStep.Recreate
    }

    /** Runs [recognition] to its end, the app's output starting, or cancellation. */
    private suspend fun recognizeOnce(recognition: LocalRecognition): RecognitionOutcome = coroutineScope {
        val events = recognition.events.produceIn(this)
        val outputStarted = if (config.pauseDuringOutput) async { memory.output.first { it } } else null
        val turn = LocalTurn(config, out)
        try {
            var outcome: RecognitionOutcome? = null
            while (outcome == null) outcome = nextOutcome(events, outputStarted, recognition, turn)
            turn.close()
            outcome
        } finally {
            events.cancel()
            outputStarted?.cancel()
        }
    }

    private suspend fun nextOutcome(
        events: ReceiveChannel<LocalRecognitionEvent>,
        outputStarted: Deferred<Boolean>?,
        recognition: LocalRecognition,
        turn: LocalTurn,
    ): RecognitionOutcome? = select {
        events.onReceiveCatching { onEvent(it.getOrNull(), turn) }
        memory.commits.onReceive {
            recognition.finish()
            null
        }
        outputStarted?.onAwait { RecognitionOutcome.Paused }
    }

    /** Handles one event; returns how the recognition ended once it has ([event] null: it ended without saying). */
    private suspend fun onEvent(event: LocalRecognitionEvent?, turn: LocalTurn): RecognitionOutcome? = when (event) {
        null -> RecognitionOutcome.Failed(LocalRecognitionError.OTHER, turn.ready)
        is LocalRecognitionEvent.Final -> RecognitionOutcome.Heard.also { turn.final(event.text, memory.output.value) }
        is LocalRecognitionEvent.Failed -> RecognitionOutcome.Failed(event.error, turn.ready)
        else -> null.also { onProgress(event, turn) }
    }

    /** An event of a recognition still under way. */
    private suspend fun onProgress(event: LocalRecognitionEvent, turn: LocalTurn) {
        when (event) {
            LocalRecognitionEvent.Ready -> ready(turn)
            is LocalRecognitionEvent.Level -> out.send(VoiceEarEvent.InputLevel(event.level))
            is LocalRecognitionEvent.Partial -> turn.partial(event.text)
            LocalRecognitionEvent.EndOfSpeech -> turn.endOfSpeech()
            else -> Unit
        }
    }

    private suspend fun ready(turn: LocalTurn) {
        turn.ready = true
        if (announced) return
        announced = true
        out.send(VoiceEarEvent.ListeningStarted)
    }
}

/** One recognition's turn as the loop sees it: whether it heard the person start, and how it ends. */
private class LocalTurn(private val config: LocalEarConfig, private val out: ProducerScope<VoiceEarEvent>) {
    var ready = false
    private var spoke = false
    private var ended = false
    private var closed = false
    private var endMark: TimeMark? = null

    suspend fun partial(text: VoiceText) {
        if (text.isBlank) return
        if (!spoke) {
            spoke = true
            out.send(VoiceEarEvent.SpeechStarted)
        }
        out.send(VoiceEarEvent.PartialTranscript(text))
    }

    suspend fun endOfSpeech() {
        endMark = config.timeSource.markNow()
        if (!spoke || ended) return
        ended = true
        out.send(VoiceEarEvent.SpeechEnded)
    }

    /** The final result: one utterance, or an empty end when it is dropped or the app is speaking. */
    suspend fun final(text: VoiceText, outputActive: Boolean) {
        val reason = when {
            outputActive && config.pauseDuringOutput -> TurnDiscardReason.OUTPUT_ACTIVE
            else -> config.transcripts.discardReason(text, EarTurnReport.Unmeasured)
        }
        if (reason != null) {
            config.observer.discarded(reason)
            return
        }
        closed = true
        config.observer.heard(text.value.length, endMark?.elapsedNow())
        out.send(VoiceEarEvent.EndOfTurn(VoiceUtterance(text)))
    }

    /** A turn the loop heard start must end, or the loop would wait for it: an empty utterance. */
    suspend fun close() {
        if (closed || !spoke) return
        closed = true
        out.send(VoiceEarEvent.EndOfTurn(VoiceUtterance(VoiceText.Empty)))
    }
}
