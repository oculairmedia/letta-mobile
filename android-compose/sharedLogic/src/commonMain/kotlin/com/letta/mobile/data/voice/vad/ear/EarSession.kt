package com.letta.mobile.data.voice.vad.ear

import com.letta.mobile.data.voice.CapturedAudioHandle
import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
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
import kotlin.time.DurationUnit

/** A finished turn's audio under its handle; [wipe] zeroes it. */
internal class CapturedTurn(val handle: CapturedAudioHandle, val samples: ShortArray) {
    fun wipe() = samples.fill(0)
}

/** What an ear keeps between listens: the retained capture, pending requests and the output hint. */
internal class EarMemory {
    private val retained = MutableStateFlow<CapturedTurn?>(null)
    private val handles = MutableStateFlow(0L)
    private val closed = MutableStateFlow(false)
    val retries = Channel<CapturedTurn>(Channel.CONFLATED) { it.wipe() }
    val commits = Channel<Unit>(Channel.CONFLATED)
    val outputActive = MutableStateFlow(false)

    val isClosed: Boolean get() = closed.value

    fun nextHandle(): CapturedAudioHandle = CapturedAudioHandle(handles.updateAndGet { it + 1 })

    /** Keeps [turn] for one retry, wiping whatever was kept before. */
    fun retain(turn: CapturedTurn) {
        if (isClosed) return turn.wipe()
        retained.getAndUpdate { turn }?.wipe()
    }

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
        while (true) retries.tryReceive().getOrNull()?.wipe() ?: break
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
    private val turns = Channel<CapturedTurn>(Channel.UNLIMITED) { it.wipe() }
    private var started = false

    suspend fun run() {
        memory.drainCommits()
        if (memory.isClosed) return fail(VoiceErrorCode.UNAVAILABLE)
        if (!accepts(parts.source)) return fail(VoiceErrorCode.UNSUPPORTED_FORMAT)
        out.launch { TranscriptionWorker(parts, out, turns).run() }
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
            turns.close()
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
        val probability = detector.probability(hop.samples)
        apply(tracker.onHop(HopObservation(probability, hop.end, memory.outputActive.value)), hop)
        recorder.remember(hop.samples)
    }

    private suspend fun apply(signal: TurnSignal, hop: Hop?) {
        when (signal) {
            TurnSignal.None -> if (tracker.recording) record(hop)
            TurnSignal.Candidate -> recorder.begin().also { record(hop) }
            TurnSignal.Confirmed -> record(hop).also { speechStarted() }
            TurnSignal.Abandoned -> recorder.discard()
            is TurnSignal.Ended -> record(hop).also { turns.send(CapturedTurn(memory.nextHandle(), recorder.finish())) }
        }
    }

    private fun record(hop: Hop?) {
        hop?.let { recorder.record(it.samples) }
    }

    private suspend fun speechStarted() {
        memory.forget()
        out.send(VoiceEarEvent.SpeechStarted)
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
            return out.send(VoiceEarEvent.EndOfTurn(VoiceUtterance(outcome.getOrThrow())))
        }
        val failure = VoiceError(codeOf(error), VoicePort.EAR)
        if (job.isRetry) {
            job.turn.wipe()
            return out.send(VoiceEarEvent.Failed(failure))
        }
        memory.retain(job.turn)
        out.send(VoiceEarEvent.Failed(failure, retry = job.turn.handle))
    }

    private fun codeOf(error: Throwable): VoiceErrorCode = when (error) {
        is CancellationException -> throw error
        is SpeechEndpointException -> error.code
        is VoiceAudioException -> error.code
        else -> VoiceErrorCode.TRANSCRIPTION_FAILED
    }
}
