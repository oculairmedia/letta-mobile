package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.loop.machine.LoopEffect
import com.letta.mobile.data.voice.loop.machine.LoopInput
import com.letta.mobile.data.voice.loop.machine.LoopModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opens and closes the ear to match the model, tags its events with a generation so events from a
 * closed ear are dropped, and tells it when output plays. An ear that does not let go within the
 * release budget stays held: no second ear is opened until the first has confirmed its release,
 * and every attempt meanwhile reports UNRESPONSIVE. An ear whose collection threw while being
 * released has not confirmed its release either: it stays held for this loop's lifetime, since the
 * ear port offers nothing else to retry. Used from the loop's processing coroutine.
 */
internal class EarSupervisor(private val wiring: LoopWiring) {
    private var job: Job? = null
    private var releasedCleanly: CompletableDeferred<Boolean>? = null
    private var stopping = false
    private var generation = 0L
    private var outputHint = false

    /** True when [input] is an ear event from an ear that is no longer running. */
    fun isStale(input: LoopInput): Boolean = input is LoopInput.FromEar && input.generation != generation

    /** True for effects that call into the running ear (applied after the microphone opens). */
    fun handles(effect: LoopEffect): Boolean = effect is LoopEffect.CommitTurn || effect is LoopEffect.RetryCapture

    /**
     * Calls into the ear for [effect]; a retry the ear can no longer serve becomes a CAPTURE_EXPIRED
     * fault, and a call that throws an ENGINE_FAILED fault.
     */
    fun call(effect: LoopEffect): LoopInput? = when (effect) {
        LoopEffect.CommitTurn -> wiring.call(VoicePort.EAR) { wiring.engines.ear.commitTurn() }
        is LoopEffect.RetryCapture -> retry(effect)
        else -> null
    }

    private fun retry(effect: LoopEffect.RetryCapture): LoopInput? {
        var accepted = false
        val fault = wiring.call(VoicePort.EAR) { accepted = wiring.engines.ear.retry(effect.handle) }
        return fault ?: if (accepted) null else expired()
    }

    private fun expired(): LoopInput = LoopInput.Fault(VoiceError(VoiceErrorCode.CAPTURE_EXPIRED, VoicePort.EAR))

    /** Opens or closes the ear to match [model]; closing waits (bounded) for the ear to let go. */
    suspend fun reconcile(model: LoopModel): LoopInput? {
        val running = job ?: return startIfWanted(model)
        return when {
            !stopping && model.micWanted -> null
            stopping && !model.micWanted -> null
            else -> release(running) ?: startIfWanted(model)
        }
    }

    /** Closes the ear for good, waiting (bounded) for it to let go; null when it has (or none was open). */
    suspend fun close(): LoopInput? = job?.let { release(it) }

    /** Tells the ear whether the app's own speech is playing, on change. */
    fun reportOutput(model: LoopModel): LoopInput? {
        if (model.outputActive == outputHint) return null
        outputHint = model.outputActive
        return wiring.call(VoicePort.EAR) { wiring.engines.ear.setOutputActive(outputHint) }
    }

    private fun startIfWanted(model: LoopModel): LoopInput? {
        if (!model.micWanted) return null
        val current = ++generation
        val clean = CompletableDeferred<Boolean>()
        releasedCleanly = clean
        job = wiring.scope.launch { listen(current, clean) }
        return null
    }

    /** Stops [running] (once) and waits, bounded, for it to let go; it stays held while it has not. */
    private suspend fun release(running: Job): LoopInput? {
        if (!stopping) {
            stopping = true
            generation++
            running.cancel()
        }
        val clean = withTimeoutOrNull(wiring.config.releaseTimeout) {
            running.join()
            // An ear job cancelled before it ever ran never opened the ear: nothing to release.
            releasedCleanly?.takeIf { it.isCompleted }?.await() ?: true
        }
        if (clean != true) return wiring.unresponsive(VoicePort.EAR)
        job = null
        stopping = false
        return null
    }

    /** Collects the ear; [clean] records whether its collection ended without throwing (cancellation is clean). */
    private suspend fun listen(current: Long, clean: CompletableDeferred<Boolean>) {
        val outcome = runCatching { wiring.engines.ear.listen().collect { wiring.inputs.send(LoopInput.FromEar(it, current)) } }
        val error = outcome.exceptionOrNull()
        clean.complete(error == null || error is CancellationException)
        if (error is CancellationException) throw error
        val ended = VoiceEarEvent.Failed(VoiceError(VoiceErrorCode.ENGINE_FAILED, VoicePort.EAR))
        wiring.inputs.send(LoopInput.FromEar(ended, current))
    }
}
