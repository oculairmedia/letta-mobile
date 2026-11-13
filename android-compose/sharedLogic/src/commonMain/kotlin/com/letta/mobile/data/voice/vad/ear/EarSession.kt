package com.letta.mobile.data.voice.vad.ear

import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.VoiceText
import com.letta.mobile.data.voice.VoiceUtterance
import com.letta.mobile.data.voice.audio.AudioClip
import com.letta.mobile.data.voice.audio.AudioEncoding
import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.PcmFormat
import com.letta.mobile.data.voice.audio.pcm.PcmAudio
import com.letta.mobile.data.voice.audio.pcm.PcmLevels
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import com.letta.mobile.data.voice.audio.pcm.WavCodec
import com.letta.mobile.data.voice.endpoint.SpeechEndpointException
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.vad.CaptureRecorder
import com.letta.mobile.data.voice.vad.Hop
import com.letta.mobile.data.voice.vad.HopObservation
import com.letta.mobile.data.voice.vad.HopSplitter
import com.letta.mobile.data.voice.vad.SpeechDetector
import com.letta.mobile.data.voice.vad.TurnEndReason
import com.letta.mobile.data.voice.vad.TurnSignal
import com.letta.mobile.data.voice.vad.TurnTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * A finished turn's audio under its handle, with what was measured about it and the speech
 * generation it belongs to; [wipe] zeroes the audio.
 */
internal class CapturedTurn(
    val handle: CapturedAudioHandle,
    val samples: ShortArray,
    val report: EarTurnReport = EarTurnReport.Unmeasured,
    val generation: Long = 0,
) {
    fun wipe() = samples.fill(0)
}

/**
 * What an ear keeps between listens: the retained capture, pending requests and the output hint.
 * Closing and a new turn's speech race with the transcription worker and [VadVoiceEar.retry] on
 * other threads, so each hand-over stores first and checks after, while [close] and
 * [speechStarted] flag first and wipe after: whichever runs second sees the other.
 */
internal class EarMemory {
    private val retained = MutableStateFlow<CapturedTurn?>(null)
    private val handles = MutableStateFlow(0L)
    private val speech = MutableStateFlow(0L)
    private val closed = MutableStateFlow(false)
    val retries = Channel<CapturedTurn>(Channel.CONFLATED) { it.wipe() }
    val commits = Channel<Unit>(Channel.CONFLATED)
    val outputActive = MutableStateFlow(false)

    val isClosed: Boolean get() = closed.value

    /** Advances each time a turn's speech starts; a capture from an older generation has expired. */
    val speechGeneration: Long get() = speech.value

    fun nextHandle(): CapturedAudioHandle = CapturedAudioHandle(handles.updateAndGet { it + 1 })

    /** A new turn's speech started: the kept capture, and any still being transcribed, expire. */
    fun speechStarted() {
        speech.updateAndGet { it + 1 }
        forget()
    }

    /**
     * Keeps [turn] for one retry, wiping whatever was kept before. Returns false, with [turn] wiped,
     * when the ear is closed or newer speech has started.
     */
    fun retain(turn: CapturedTurn): Boolean {
        if (isCurrent(turn)) {
            retained.getAndUpdate { turn }?.wipe()
            if (isCurrent(turn)) return true
            retained.compareAndSet(turn, null)
        }
        turn.wipe()
        return false
    }

    /** Queues [turn] for its retry; false, with [turn] wiped, when the ear was closed meanwhile. */
    fun requestRetry(turn: CapturedTurn): Boolean {
        retries.trySend(turn)
        if (!isClosed) return true
        drainRetries()
        return false
    }

    private fun isCurrent(turn: CapturedTurn): Boolean = !isClosed && turn.generation == speech.value

    /** Wipes the kept capture, if any. */
    fun forget() {
        retained.getAndUpdate { null }?.wipe()
    }

    /** Hands over the kept capture named [handle] for its one retry. */
    fun take(handle: CapturedAudioHandle): CapturedTurn? {
        val held = retained.value
        if (isClosed || held?.handle != handle) return null
        return if (retained.compareAndSet(held, null)) held else null
    }

    fun drainCommits() {
        do {
            val drained = commits.tryReceive().isSuccess
        } while (drained)
    }

    fun close() {
        closed.value = true
        forget()
        drainRetries()
    }

    private fun drainRetries() {
        while (true) retries.tryReceive().getOrNull()?.wipe() ?: break
    }
}

/**
 * Finished turns waiting for the transcription worker: at most [capacity], each within the hard
 * cap, so the audio held stays bounded however far transcription falls behind. A turn beyond that
 * is refused and wiped; [discard] wipes whatever is still queued when the listen ends.
 */
internal class PendingTurns(capacity: Int = MAX_PENDING_TURNS) {
    private val queue = Channel<CapturedTurn>(capacity) { it.wipe() }

    val turns: ReceiveChannel<CapturedTurn> get() = queue

    /** Queues [turn]; false, with [turn] wiped, when the queue is full. */
    fun offer(turn: CapturedTurn): Boolean {
        val queued = queue.trySend(turn).isSuccess
        if (!queued) turn.wipe()
        return queued
    }

    /** Drops and wipes every queued turn; nothing can be queued after. */
    fun discard() = queue.cancel()

    companion object {
        /** Turns that may wait for transcription at once. */
        const val MAX_PENDING_TURNS = 4
    }
}

/** Everything one listen needs from its ear. */
internal class EarParts(
    val source: AudioFrameSource,
    val transcriber: SpeechTranscriber,
    val config: VadEarConfig,
    val memory: EarMemory,
)

private sealed interface FrameItem {
    class Frame(val frame: AudioFrame) : FrameItem

    class Failed(val cause: Throwable) : FrameItem
}

private class TranscriptionJob(val turn: CapturedTurn, val isRetry: Boolean)

/** One collection of [VadVoiceEar.listen]: frames in, events out, transcriptions in a worker. */
internal class EarSession(private val parts: EarParts, private val out: ProducerScope<VoiceEarEvent>) {
    private val memory = parts.memory
    private val format = parts.config.format
    private val detector = parts.config.detectors.create(format)
    private val tracker = TurnTracker(parts.config.policy, format, detector.hop)
    private val splitter = HopSplitter(detector.hop)
    private val recorder = CaptureRecorder(samplesOf(parts.config.policy.preRoll), limit())
    private val pending = PendingTurns()
    private val observer = parts.config.observer
    private var started = false
    private var peakDb = SILENT_DB

    suspend fun run() {
        memory.drainCommits()
        if (memory.isClosed) return fail(VoiceErrorCode.UNAVAILABLE)
        if (!accepts(parts.source)) return fail(VoiceErrorCode.UNSUPPORTED_FORMAT)
        out.launch { TranscriptionWorker(parts, out, pending.turns).run() }
        val frames = parts.source.frames()
            .map<AudioFrame, FrameItem> { FrameItem.Frame(it) }
            .catch { emit(FrameItem.Failed(it)) }
            .produceIn(out)
        try {
            while (true) {
                val ended = select<VoiceErrorCode?> {
                    frames.onReceiveCatching { onFrameItem(it.getOrNull()) }
                    memory.commits.onReceive { onCommit() }
                }
                if (ended != null) return fail(ended)
            }
        } finally {
            recorder.wipe()
            splitter.wipe()
            pending.discard()
        }
    }

    private fun accepts(source: AudioFrameSource): Boolean =
        source.format.sampleRateHz == format.sampleRateHz && source.format.channels == format.channels

    /** Handles one item; returns the failure code when the source has ended. */
    private suspend fun onFrameItem(item: FrameItem?): VoiceErrorCode? = when (item) {
        is FrameItem.Frame -> null.also { onFrame(item.frame) }
        is FrameItem.Failed -> sourceCodeOf(item.cause)
        null -> VoiceErrorCode.ENGINE_FAILED
    }

    private suspend fun onCommit(): VoiceErrorCode? {
        apply(tracker.commit(), null)
        return null
    }

    private suspend fun onFrame(frame: AudioFrame) {
        if (!started) {
            started = true
            out.send(VoiceEarEvent.ListeningStarted)
        }
        out.send(VoiceEarEvent.InputLevel(PcmLevels.level(frame.samples)))
        splitter.split(frame).forEach { hop -> onHop(hop) }
    }

    private suspend fun onHop(hop: Hop) {
        val outputActive = memory.outputActive.value
        detector.setOutputActive(outputActive)
        val probability = detector.probability(hop.samples)
        val signal = tracker.onHop(HopObservation(probability, hop.end, outputActive))
        if (signal == TurnSignal.Candidate) peakDb = SILENT_DB
        if (tracker.recording || signal is TurnSignal.Ended) peakDb = maxOf(peakDb, PcmLevels.dbfs(hop.samples))
        apply(signal, hop)
        recorder.remember(hop.samples)
    }

    private suspend fun apply(signal: TurnSignal, hop: Hop?) {
        when (signal) {
            TurnSignal.None -> if (tracker.recording) record(hop)
            TurnSignal.Candidate -> {
                recorder.begin()
                record(hop)
            }
            TurnSignal.Confirmed -> speechStarted(hop)
            is TurnSignal.Abandoned -> {
                recorder.discard()
                observer.turnDiscarded(turnReport(tracker, peakDb, detector, format.sampleRateHz), signal.reason)
            }
            is TurnSignal.Ended -> endTurn(hop, signal.reason)
        }
    }

    private fun record(hop: Hop?) {
        if (hop != null) recorder.record(hop.samples)
    }

    private suspend fun speechStarted(hop: Hop?) {
        record(hop)
        memory.speechStarted()
        observer.turnOpened()
        out.send(VoiceEarEvent.SpeechStarted)
    }

    private suspend fun endTurn(hop: Hop?, reason: TurnEndReason) {
        record(hop)
        val report = turnReport(tracker, peakDb, detector, format.sampleRateHz)
        observer.turnClosed(report, reason)
        val turn = CapturedTurn(memory.nextHandle(), recorder.finish(), report, memory.speechGeneration)
        if (!pending.offer(turn)) out.send(VoiceEarEvent.Failed(VoiceError(VoiceErrorCode.TRANSCRIPTION_FAILED, VoicePort.EAR)))
    }

    private suspend fun fail(code: VoiceErrorCode): Nothing {
        out.send(VoiceEarEvent.Failed(VoiceError(code, VoicePort.EAR)))
        awaitCancellation()
    }

    private fun sourceCodeOf(error: Throwable): VoiceErrorCode = when (error) {
        is CancellationException -> throw error
        is VoiceAudioException -> error.code
        else -> VoiceErrorCode.ENGINE_FAILED
    }

    private fun limit(): Long = samplesOf(parts.config.policy.maxTurn) + samplesOf(parts.config.policy.preRoll) + detector.hop.samples

    private fun samplesOf(duration: Duration): Long = (duration.toDouble(DurationUnit.SECONDS) * format.sampleRateHz).toLong()
}

/** Transcribes finished turns and retries in order, one at a time, and reports the outcome. */
private class TranscriptionWorker(
    private val parts: EarParts,
    private val out: ProducerScope<VoiceEarEvent>,
    private val turns: ReceiveChannel<CapturedTurn>,
) {
    private val memory = parts.memory
    private val format = parts.config.format

    suspend fun run() {
        while (true) {
            val job = select<TranscriptionJob?> {
                turns.onReceiveCatching { result -> result.getOrNull()?.let { TranscriptionJob(it, isRetry = false) } }
                memory.retries.onReceive { TranscriptionJob(it, isRetry = true) }
            } ?: return
            deliver(job)
        }
    }

    private suspend fun deliver(job: TranscriptionJob) {
        val pcm = PcmAudio(job.turn.samples, PcmFormat(format.sampleRateHz, format.channels))
        val clip = AudioClip(WavCodec.encode(pcm), AudioEncoding.WAV)
        val outcome = runCatching { parts.transcriber.transcribe(clip) }
        clip.bytes.fill(0)
        val error = outcome.exceptionOrNull()
        if (error is CancellationException) {
            job.turn.wipe()
            throw error
        }
        if (error == null) {
            job.turn.wipe()
            return out.send(VoiceEarEvent.EndOfTurn(judged(outcome.getOrThrow(), job.turn.report)))
        }
        val failure = VoiceError(codeOf(error), VoicePort.EAR)
        if (job.isRetry) {
            job.turn.wipe()
            return out.send(VoiceEarEvent.Failed(failure))
        }
        val kept = memory.retain(job.turn)
        out.send(VoiceEarEvent.Failed(failure, retry = job.turn.handle.takeIf { kept }))
    }

    /** The transcript as the turn's utterance, or an empty one (the loop listens on) when the policy drops it. */
    private fun judged(text: VoiceText, report: EarTurnReport): VoiceUtterance {
        val reason = parts.config.transcripts.discardReason(text, report)
        if (reason != null) {
            parts.config.observer.turnDiscarded(report, reason)
            return VoiceUtterance(VoiceText.Empty)
        }
        parts.config.observer.transcriptAccepted(text.value.length)
        return VoiceUtterance(text)
    }

    private fun codeOf(error: Throwable): VoiceErrorCode = when (error) {
        is CancellationException -> throw error
        is SpeechEndpointException -> error.code
        is VoiceAudioException -> error.code
        else -> VoiceErrorCode.TRANSCRIPTION_FAILED
    }
}

private const val SILENT_DB = -120.0

/** What [tracker] and [detector] measured about the turn that just ended or was dropped. */
private fun turnReport(
    tracker: TurnTracker,
    peakDb: Double,
    detector: SpeechDetector,
    sampleRateHz: Int,
): EarTurnReport {
    val voiced = (tracker.voicedSamples.toDouble() / sampleRateHz).seconds
    return EarTurnReport(voiced, peakDb, detector.noiseFloorDb)
}
