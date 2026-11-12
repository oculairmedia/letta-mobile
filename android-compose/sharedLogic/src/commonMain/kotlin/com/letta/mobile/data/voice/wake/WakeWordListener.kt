package com.letta.mobile.data.voice.wake

import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.WakePhrase
import com.letta.mobile.data.voice.WakeWordEngineId
import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update

/** Whether the listener is running. */
enum class WakeListenerState {
    /** Not listening. */
    STOPPED,

    /** Listening for the phrases. */
    LISTENING,

    /** No engine can run (or the running one failed); voice continues with push-to-talk. */
    UNAVAILABLE,
}

/** What the listener reports. */
sealed interface WakeWordEvent {
    /** The frame source is open and the engine is listening. */
    data object Listening : WakeWordEvent

    /** A hit passed every gate. */
    data class Detected(
        /** The hit. */
        val hit: WakeWordHit,
    ) : WakeWordEvent

    /** The engine applied a phrase list; one outcome per phrase. */
    data class PhrasesApplied(
        /** The outcomes, in phrase order. */
        val outcomes: List<PhraseOutcome>,
    ) : WakeWordEvent

    /** No engine can run, or the running engine failed; the frame source is already released. */
    data class Unavailable(
        /** Why, with a fixed code. */
        val error: VoiceError,
        /** Engines passed over, and why. */
        val skipped: List<SkippedEngine> = emptyList(),
    ) : WakeWordEvent

    /** The frame source ended. */
    data object Stopped : WakeWordEvent
}

/** Which engine to prefer and how to gate hits. */
data class WakeWordListenerConfig(
    /** The engine to try first. */
    val preferredEngine: WakeWordEngineId? = null,
    /** The gates. */
    val policy: WakeWordPolicy = WakeWordPolicy(),
)

/**
 * The one wake-word orchestrator. It selects an engine from [engines], refuses a [source] whose
 * format differs from the engine's (nothing resamples), feeds frames to the session and gates raw
 * hits: per-phrase sensitivity, a cooldown after each hit, suppression while the app speaks and for
 * a tail after, then the policy's confirmer.
 *
 * Collecting [events] starts listening; [WakeWordEvent.Listening] is reported once the first frame
 * arrives (the source is open). Cancelling the collection stops it, and the collection does
 * not complete until the frame source is released and the session closed. With no usable engine the
 * frame source is never opened. Phrase changes ([setPhrases]) apply live, without reopening.
 */
class WakeWordListener(
    private val source: AudioFrameSource,
    private val engines: WakeWordEngines,
    private val config: WakeWordListenerConfig = WakeWordListenerConfig(),
) {
    private val phrases = MutableStateFlow<List<WakePhrase>>(emptyList())
    private val appSpeech = MutableStateFlow(AppSpeech())
    private val mutableState = MutableStateFlow(WakeListenerState.STOPPED)
    private val running = MutableStateFlow(false)

    /** Whether the listener is running. */
    val state: StateFlow<WakeListenerState> = mutableState.asStateFlow()

    /**
     * Detections and lifecycle while collected. One collection runs at a time: a second concurrent
     * collection receives [WakeWordEvent.Unavailable] with [VoiceErrorCode.BUSY] at once and opens
     * nothing, leaving the running one and [state] untouched.
     */
    val events: Flow<WakeWordEvent> = flow {
        if (!running.compareAndSet(expect = false, update = true)) {
            emit(WakeWordEvent.Unavailable(VoiceError(VoiceErrorCode.BUSY, VoicePort.ACTIVATION)))
            return@flow
        }
        try {
            run()
        } finally {
            running.value = false
        }
    }

    /** The phrases to listen for (copied); applied at the next frame. */
    fun setPhrases(list: List<WakePhrase>) {
        phrases.value = list.toList()
    }

    /**
     * Whether the app's own speech is playing (from the voice loop), for suppression. A start and a
     * stop between two frames still begin the echo tail.
     */
    fun setAppSpeaking(speaking: Boolean) {
        appSpeech.update { current ->
            when {
                !speaking -> current.copy(speaking = false)
                current.speaking -> current
                else -> AppSpeech(speaking = true, episode = current.episode + 1)
            }
        }
    }

    private suspend fun FlowCollector<WakeWordEvent>.run() {
        when (val selection = engines.open(config.preferredEngine)) {
            is WakeWordSelection.Unavailable -> unavailable(codeFor(selection.skipped), selection.skipped)
            is WakeWordSelection.Opened -> listen(selection)
        }
    }

    /** Runs the session to its end, then reports how it ended: only after the source is released and the session closed. */
    private suspend fun FlowCollector<WakeWordEvent>.listen(selection: WakeWordSelection.Opened) {
        val ending = runSession(selection)
        mutableState.value = if (ending is WakeWordEvent.Stopped) WakeListenerState.STOPPED else WakeListenerState.UNAVAILABLE
        emit(ending)
    }

    private suspend fun FlowCollector<WakeWordEvent>.runSession(selection: WakeWordSelection.Opened): WakeWordEvent {
        val session = selection.session
        try {
            if (source.format != selection.binding.engine.descriptor.format) {
                return unavailableEvent(VoiceErrorCode.UNSUPPORTED_FORMAT, selection.skipped)
            }
            val run = ListenRun(session, source.format, config.policy)
            val outcome = runCatching { source.frames().collect { frame -> process(run, frame) } }
            val error = outcome.exceptionOrNull()
            if (error is CancellationException) throw error
            return if (error == null) WakeWordEvent.Stopped else unavailableEvent(VoiceErrorCode.ENGINE_FAILED, selection.skipped)
        } finally {
            session.close()
            mutableState.compareAndSet(WakeListenerState.LISTENING, WakeListenerState.STOPPED)
        }
    }

    private fun unavailableEvent(code: VoiceErrorCode, skipped: List<SkippedEngine>): WakeWordEvent =
        WakeWordEvent.Unavailable(VoiceError(code, VoicePort.ACTIVATION), skipped)

    /** The first frame proves the source is open: only then is the listener reported as listening. */
    private suspend fun FlowCollector<WakeWordEvent>.process(run: ListenRun, frame: AudioFrame) {
        if (mutableState.value != WakeListenerState.LISTENING) {
            mutableState.value = WakeListenerState.LISTENING
            emit(WakeWordEvent.Listening)
        }
        run.onFrame(frame, ListenInput(phrases.value, appSpeech.value)).forEach { emit(it) }
    }

    private suspend fun FlowCollector<WakeWordEvent>.unavailable(code: VoiceErrorCode, skipped: List<SkippedEngine>) {
        mutableState.value = WakeListenerState.UNAVAILABLE
        emit(WakeWordEvent.Unavailable(VoiceError(code, VoicePort.ACTIVATION), skipped))
    }

    private fun codeFor(skipped: List<SkippedEngine>): VoiceErrorCode = when (skipped.firstOrNull()?.reason) {
        null -> VoiceErrorCode.UNAVAILABLE
        WakeWordOpenFailure.ASSET_MISSING -> VoiceErrorCode.ASSET_MISSING
        WakeWordOpenFailure.ASSET_CORRUPT -> VoiceErrorCode.PREPARATION_FAILED
        WakeWordOpenFailure.UNSUPPORTED_PLATFORM -> VoiceErrorCode.UNAVAILABLE
        WakeWordOpenFailure.ENGINE_FAILED -> VoiceErrorCode.ENGINE_FAILED
    }
}

/** What the listener wants for one frame: the phrases and the app's speech. */
private data class ListenInput(val phrases: List<WakePhrase>, val speech: AppSpeech)

/** One listening run: the session, its applied phrases and the gates. Used from one coroutine. */
private class ListenRun(
    private val session: WakeWordSession,
    private val format: AudioFrameFormat,
    private val policy: WakeWordPolicy,
) {
    private var applied: List<WakePhrase>? = null
    private val cooldown = CooldownGate(policy.cooldown)
    private val suppression = OutputSuppressionGate(policy.speechTail)

    suspend fun onFrame(frame: AudioFrame, input: ListenInput): List<WakeWordEvent> {
        val applying = applyPhrases(input.phrases)
        val at = format.timeAt(frame.start)
        val suppressed = suppression.suppressed(input.speech, at)
        val hit = session.process(frame) ?: return applying
        return if (admits(hit, suppressed)) applying + WakeWordEvent.Detected(hit) else applying
    }

    private suspend fun applyPhrases(wanted: List<WakePhrase>): List<WakeWordEvent> {
        if (wanted == applied) return emptyList()
        applied = wanted
        return listOf(WakeWordEvent.PhrasesApplied(session.setPhrases(wanted)))
    }

    /** The cooldown starts only from a hit the confirmer accepted. */
    private suspend fun admits(hit: WakeWordHit, suppressed: Boolean): Boolean {
        val at = format.timeAt(hit.position)
        val eligible = when {
            suppressed -> false
            !SensitivityGate.passes(hit) -> false
            else -> !cooldown.coolingAt(at)
        }
        val accepted = eligible && policy.confirmer.confirm(hit)
        if (accepted) cooldown.record(at)
        return accepted
    }
}
