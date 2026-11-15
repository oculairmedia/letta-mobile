package com.letta.mobile.data.voice.local

import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.engine.VoiceEar
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.vad.TurnDiscardReason
import com.letta.mobile.data.voice.vad.ear.TranscriptPolicy
import com.letta.mobile.data.voice.vad.ear.VadEarConfig
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** Why a [LocalVoiceEar] stopped using the local recogniser. */
enum class LocalHandoverReason {
    /** The device has no recogniser for the language. */
    UNAVAILABLE,

    /** The recogniser does not support the language, or its offline model is missing. */
    LANGUAGE,

    /** The recogniser kept failing past its retries. */
    KEEPS_FAILING,
}

/**
 * What a [LocalVoiceEar] decided, for diagnostics. Every call carries reasons, counts and durations
 * only, never words. Called from the listening coroutine; must not block.
 */
interface LocalEarObserver {
    /**
     * A final result of [characters] characters was accepted, [sinceEndOfSpeech] after the
     * recogniser heard the person stop (null when it did not say).
     */
    fun heard(characters: Int, sinceEndOfSpeech: Duration?) = Unit

    /** A final result was dropped for [reason]; nothing is sent. */
    fun discarded(reason: TurnDiscardReason) = Unit

    /** A recognition failed with [error]; [attempt] counts the failures in a row (0 for silence). */
    fun failed(error: LocalRecognitionError, attempt: Int) = Unit

    /** The ear stopped using the local recogniser for [reason]; [toServer] when the server ear took over. */
    fun handedOver(reason: LocalHandoverReason, toServer: Boolean) = Unit

    /** Shared observers. */
    companion object {
        /** Observes nothing. */
        val None: LocalEarObserver = object : LocalEarObserver {}
    }
}

/** The ear that takes over when the local recogniser cannot go on: the server ear. */
fun interface VoiceEarFallback {
    /** Builds the fallback ear; called at most once per [LocalVoiceEar]. */
    fun ear(): VoiceEar
}

/** How a [LocalVoiceEar] listens. */
data class LocalEarConfig(
    /** The language to recognise, as a BCP 47 tag; null uses the device's language. */
    val language: String? = null,
    /**
     * Pause the recogniser while the app's output is active and for [outputTail] after it, so the
     * app's own speech is never transcribed. Turn off only where the recogniser's input is
     * echo-cancelled ([LocalSpeechRecognizer.echoCancelled]).
     */
    val pauseDuringOutput: Boolean = true,
    /** How long after the output ends it may still be heard (room reverb, device latency). */
    val outputTail: Duration = VadEarConfig.DEFAULT_OUTPUT_TAIL,
    /** Which final results are dropped instead of sent. */
    val transcripts: TranscriptPolicy = LOCAL_TRANSCRIPTS,
    /** The waits before each retry after a failure in a row; one failure more than these hands over. */
    val retryDelays: List<Duration> = DEFAULT_RETRY_DELAYS,
    /** Hears the ear's decisions, for diagnostics. */
    val observer: LocalEarObserver = LocalEarObserver.None,
    /** Times end of speech to final result, for [observer]. */
    val timeSource: TimeSource = TimeSource.Monotonic,
) {
    init {
        require(!outputTail.isNegative()) { "Negative output tail" }
    }

    /** Defaults. */
    companion object {
        /**
         * Only the shape rules: an empty result, or one without words. The stock-phrase and filler
         * rules are for recognisers like Whisper that write words for noise; a local recogniser does
         * not, and it reports no voiced duration, so those rules would drop a real "thank you".
         */
        val LOCAL_TRANSCRIPTS: TranscriptPolicy = TranscriptPolicy(stockPhrases = emptySet(), fillerWords = emptySet())

        /** Four retries, waiting longer each time, before handing over. */
        val DEFAULT_RETRY_DELAYS: List<Duration> = listOf(250.milliseconds, 500.milliseconds, 1.seconds, 2.seconds)
    }
}

/**
 * A [VoiceEar] over the device's own recogniser ([LocalSpeechRecognizer]): every platform's local
 * listening goes through it, so the behaviour is the same wherever a recogniser exists.
 *
 * Each recognition is one utterance: words so far arrive as [VoiceEarEvent.PartialTranscript] (the
 * first non-blank one is also [VoiceEarEvent.SpeechStarted]; the recogniser's own start of speech
 * fires on noise and is not used), the recogniser's end of speech is [VoiceEarEvent.SpeechEnded],
 * and its final result is exactly one [VoiceEarEvent.EndOfTurn] unless the
 * [LocalEarConfig.transcripts] policy drops it. The ear then listens again: the recogniser stops by
 * itself after silence, and "no match" or "speech timeout" are normal outcomes that restart it at
 * once. A failure before the recogniser was ready counts as a failure, so a recogniser that fails
 * on start is not restarted in a tight loop. [commitTurn] asks the recogniser for its result now.
 *
 * The app's own speech is never transcribed: with [LocalEarConfig.pauseDuringOutput] the
 * recognition is cancelled as soon as output is active (a turn already heard ends as an empty
 * utterance), a result that arrives while output is active is dropped, and no recognition starts
 * until output has been quiet for [LocalEarConfig.outputTail]. So only a press interrupts the reply.
 *
 * Failures: busy, network, service and audio failures recreate the recogniser and retry after
 * [LocalEarConfig.retryDelays]; past those, as when no recogniser exists for the language or the
 * language's model is missing, the ear hands over to [fallback] for the rest of its life (reported
 * once on [handover]) and that ear's events follow in the same flow. Without a fallback ("local
 * only") it reports [VoiceEarEvent.Failed] (UNAVAILABLE, UNSUPPORTED_LOCALE or ENGINE_FAILED)
 * instead. A missing recording permission is never handed over (the server ear would need it too):
 * it is reported as PERMISSION_REQUIRED.
 */
class LocalVoiceEar(
    private val recognizer: LocalSpeechRecognizer,
    private val fallback: VoiceEarFallback? = null,
    private val config: LocalEarConfig = LocalEarConfig(),
) : VoiceEar, AutoCloseable {
    private val memory = LocalEarMemory()

    /** Why the ear handed over to its fallback, once it has; null while it listens locally. */
    val handover: StateFlow<LocalHandoverReason?> = memory.handover.asStateFlow()

    override fun listen(): Flow<VoiceEarEvent> = channelFlow {
        val ear = memory.taken ?: run {
            val ended = LocalListenSession(LocalListenParts(recognizer, config, memory), this).run()
            handOver(ended) ?: run {
                send(VoiceEarEvent.Failed(VoiceError(ended.code, VoicePort.EAR)))
                awaitCancellation()
            }
        }
        ear.listen().collect { send(it) }
    }

    /** The fallback ear for [ended], now in charge; null when there is none to hand over to. */
    private fun handOver(ended: LocalGiveUp): VoiceEar? {
        val reason = ended.handover ?: return null
        val ear = fallback?.takeUnless { memory.closed }?.ear()
        config.observer.handedOver(reason, toServer = ear != null)
        if (ear == null) return null
        ear.setOutputActive(memory.output.value)
        memory.taken = ear
        memory.handover.value = reason
        return ear
    }

    override fun commitTurn() {
        memory.taken?.commitTurn() ?: memory.commits.trySend(Unit)
    }

    override fun setOutputActive(active: Boolean) {
        if (active) memory.outputSeen = true
        memory.output.value = active
        memory.taken?.setOutputActive(active)
    }

    override fun retry(handle: CapturedAudioHandle): Boolean = memory.taken?.retry(handle) ?: false

    /** Releases the fallback ear, if one took over, and refuses further local listens. */
    override fun close() {
        memory.closed = true
        (memory.taken as? AutoCloseable)?.close()
    }
}

/** What a [LocalVoiceEar] keeps between listens. */
internal class LocalEarMemory {
    val output = MutableStateFlow(false)
    val commits = Channel<Unit>(Channel.CONFLATED)
    val handover = MutableStateFlow<LocalHandoverReason?>(null)

    @Volatile
    var taken: VoiceEar? = null

    @Volatile
    var closed: Boolean = false

    /** Output was active since the last recognition started: the next one waits for its tail. */
    @Volatile
    var outputSeen: Boolean = false

    fun drainCommits() {
        do {
            val drained = commits.tryReceive().isSuccess
        } while (drained)
    }
}

/** Why a local listen ended: the code to report, and whether a fallback may take over. */
internal class LocalGiveUp(val code: VoiceErrorCode, val handover: LocalHandoverReason?)
