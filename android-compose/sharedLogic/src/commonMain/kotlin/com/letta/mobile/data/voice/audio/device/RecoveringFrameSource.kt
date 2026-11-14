package com.letta.mobile.data.voice.audio.device

import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.audio.AudioFrame
import com.letta.mobile.data.voice.audio.AudioFrameFormat
import com.letta.mobile.data.voice.audio.AudioFrameSource
import com.letta.mobile.data.voice.audio.SamplePosition
import com.letta.mobile.data.voice.audio.pcm.VoiceAudioException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How a capture recovers from a lost or failing device. */
data class CaptureRecoveryPolicy(
    /** Consecutive failed reopen attempts before the failure is reported; a delivered frame resets the count. */
    val maxAttempts: Int = 5,
    /** The wait before the first reopen; doubled for each further attempt. */
    val initialBackoff: Duration = 250.milliseconds,
    /** The longest wait between attempts. */
    val maxBackoff: Duration = 4.seconds,
    /** A device change ends the wait early once changes have been quiet for this long. */
    val deviceChangeDebounce: Duration = 500.milliseconds,
) {
    /** The wait before reopen [attempt] (1-based). */
    fun backoff(attempt: Int): Duration {
        var wait = initialBackoff
        repeat(attempt - 1) { wait = (wait * 2).coerceAtMost(maxBackoff) }
        return wait.coerceAtMost(maxBackoff)
    }

    /** Whether a failure with [code] is worth reopening for. Missing permission and wrong format never are. */
    fun retries(code: VoiceErrorCode): Boolean = code !in neverRetried

    private companion object {
        val neverRetried = setOf(VoiceErrorCode.PERMISSION_REQUIRED, VoiceErrorCode.UNSUPPORTED_FORMAT, VoiceErrorCode.CANCELLED)
    }
}

/**
 * Wraps a frame [source] so a device that fails or disappears (hot-unplug, a line error, a source
 * that ends on its own) is reopened with exponential backoff, a reopen happening early when
 * [deviceChanges] reports a change and then stays quiet for the debounce window. After
 * [CaptureRecoveryPolicy.maxAttempts] consecutive failures the last error is thrown as a
 * [VoiceAudioException]; PERMISSION_REQUIRED and UNSUPPORTED_FORMAT are thrown at once.
 *
 * Sample positions stay monotonic across reopens: each new run is rebased to continue where the
 * previous one stopped (the gap while the device was missing is not counted).
 */
class RecoveringFrameSource(
    private val source: AudioFrameSource,
    private val policy: CaptureRecoveryPolicy = CaptureRecoveryPolicy(),
    private val deviceChanges: Flow<Unit> = emptyFlow(),
) : AudioFrameSource {
    override val format: AudioFrameFormat get() = source.format

    override fun frames(): Flow<AudioFrame> = flow { RecoveryRun().run(this) }

    private inner class RecoveryRun {
        private var failures = 0
        private var next = 0L

        suspend fun run(collector: FlowCollector<AudioFrame>) {
            while (true) {
                val code = codeOf(collectOnce(collector))
                failures++
                if (!policy.retries(code) || failures > policy.maxAttempts) throw VoiceAudioException(code)
                waitBeforeReopen()
            }
        }

        /** Collects one run; returns its upstream failure (null when it ended on its own). Downstream failures propagate. */
        private suspend fun collectOnce(collector: FlowCollector<AudioFrame>): Throwable? {
            var offset: Long? = null
            var failure: Throwable? = null
            source.frames().catch { failure = it }.collect { frame ->
                val shift = offset ?: (next - frame.start.value).also { offset = it }
                failures = 0
                val start = frame.start.value + shift
                next = start + frame.samples.size / format.channels
                collector.emit(AudioFrame(frame.samples, SamplePosition(start)))
            }
            return failure
        }

        private fun codeOf(error: Throwable?): VoiceErrorCode = when (error) {
            null -> VoiceErrorCode.ENGINE_FAILED
            is CancellationException -> throw error
            is VoiceAudioException -> error.code
            else -> VoiceErrorCode.ENGINE_FAILED
        }

        @OptIn(FlowPreview::class)
        private suspend fun waitBeforeReopen() {
            withTimeoutOrNull(policy.backoff(failures)) {
                deviceChanges.debounce(policy.deviceChangeDebounce).firstOrNull() ?: awaitCancellation()
            }
        }
    }
}
