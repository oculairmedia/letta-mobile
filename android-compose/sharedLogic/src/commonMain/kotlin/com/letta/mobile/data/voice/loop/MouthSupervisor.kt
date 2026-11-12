package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.loop.machine.LoopInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the mouth. A stop runs in its own coroutine and is not abandoned when it misses the
 * release budget: the mouth stays held, every later attempt to speak or to open the microphone
 * waits (bounded) for that same stop again and reports UNRESPONSIVE while it has not confirmed.
 * Engine calls that throw become ENGINE_FAILED faults. Used from the loop's processing coroutine.
 */
internal class MouthSupervisor(private val wiring: LoopWiring) {
    private var stopping: Job? = null

    /** Hands [segment] to the mouth once any earlier stop has confirmed. */
    suspend fun speak(segment: SpeechSegment): LoopInput? =
        awaitRelease() ?: wiring.call(VoicePort.MOUTH) { wiring.engines.mouth.speak(segment) }

    /** Tells the mouth no more text is coming for now. */
    fun flush(): LoopInput? = wiring.call(VoicePort.MOUTH) { wiring.engines.mouth.flush() }

    /** Stops the mouth (once) and waits, bounded, for it to confirm; it stays held while it has not. */
    suspend fun stop(): LoopInput? {
        val running = stopping ?: wiring.scope.launch { stopQuietly() }.also { stopping = it }
        withTimeoutOrNull(wiring.config.releaseTimeout) { running.join() } ?: return wiring.unresponsive(VoicePort.MOUTH)
        stopping = null
        return null
    }

    /** Waits (bounded) for a stop that has not confirmed yet; null when the mouth is free. */
    suspend fun awaitRelease(): LoopInput? = if (stopping == null) null else stop()

    /** A stop that throws has ended all the same; only cancellation propagates. */
    private suspend fun stopQuietly() {
        val error = runCatching { wiring.engines.mouth.stop() }.exceptionOrNull()
        if (error is CancellationException) throw error
    }
}
