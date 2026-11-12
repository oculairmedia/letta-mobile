package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.engine.SpeechSegment
import com.letta.mobile.data.voice.loop.machine.LoopInput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the mouth. A stop runs in its own coroutine and only a clean return confirms the release.
 * A stop that misses the release budget is waited on again by every later attempt; a stop that
 * throws is not a release either: the mouth stays held and the next attempt starts a new stop.
 * While held, nothing is spoken, the microphone is not opened, and every attempt reports
 * UNRESPONSIVE (the code the loop uses for "cannot confirm release"). Engine calls that throw
 * become ENGINE_FAILED faults. Used from the loop's processing coroutine.
 */
internal class MouthSupervisor(private val wiring: LoopWiring) {
    private var attempt: CompletableDeferred<Boolean>? = null
    private var held = false

    /** Hands [segment] to the mouth once any earlier stop has confirmed. */
    suspend fun speak(segment: SpeechSegment): LoopInput? =
        awaitRelease() ?: wiring.call(VoicePort.MOUTH) { wiring.engines.mouth.speak(segment) }

    /** Tells the mouth no more text is coming for now. */
    fun flush(): LoopInput? = wiring.call(VoicePort.MOUTH) { wiring.engines.mouth.flush() }

    /** Stops the mouth and waits, bounded, for a clean return; it stays held until one. */
    suspend fun stop(): LoopInput? {
        val running = attempt ?: startAttempt()
        val clean = withTimeoutOrNull(wiring.config.releaseTimeout) { running.await() }
        if (clean != null) attempt = null
        held = clean != true
        return if (held) wiring.unresponsive(VoicePort.MOUTH) else null
    }

    /** Waits (bounded) for a release that has not been confirmed yet; null when the mouth is free. */
    suspend fun awaitRelease(): LoopInput? = if (held) stop() else null

    private fun startAttempt(): CompletableDeferred<Boolean> {
        val result = CompletableDeferred<Boolean>()
        attempt = result
        wiring.scope.launch { result.complete(stopCleanly()) }
        return result
    }

    /** True only when the mouth's stop returned normally; only cancellation propagates. */
    private suspend fun stopCleanly(): Boolean {
        val error = runCatching { wiring.engines.mouth.stop() }.exceptionOrNull()
        if (error is CancellationException) throw error
        return error == null
    }
}
