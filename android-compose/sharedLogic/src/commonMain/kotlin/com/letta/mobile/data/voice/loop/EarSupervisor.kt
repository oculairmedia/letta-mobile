package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.engine.VoiceEarEvent
import com.letta.mobile.data.voice.loop.machine.LoopEffect
import com.letta.mobile.data.voice.loop.machine.LoopInput
import com.letta.mobile.data.voice.loop.machine.LoopModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opens and closes the ear to match the model, tags its events with a generation so events from a
 * closed ear are dropped, and tells it when output plays. An ear that does not let go within the
 * release budget stays held: no second ear is opened until the first has confirmed its release,
 * and every attempt meanwhile reports UNRESPONSIVE. Used from the loop's processing coroutine.
 */
internal class EarSupervisor(private val wiring: LoopWiring) {
    private var job: Job? = null
    private var stopping = false
    private var generation = 0L
    private var outputHint = false

    /** True when [input] is an ear event from an ear that is no longer running. */
    fun isStale(input: LoopInput): Boolean = input is LoopInput.FromEar && input.generation != generation

    /** True for effects that call into the running ear (applied after the microphone opens). */
    fun handles(effect: LoopEffect): Boolean = effect is LoopEffect.CommitTurn || effect is LoopEffect.RetryCapture

    /** Calls into the ear for [effect]; a retry the ear can no longer serve becomes a CAPTURE_EXPIRED fault. */
    fun call(effect: LoopEffect): LoopInput? = when (effect) {
        LoopEffect.CommitTurn -> wiring.engines.ear.commitTurn().let { null }
        is LoopEffect.RetryCapture -> if (wiring.engines.ear.retry(effect.handle)) null else expired()
        else -> null
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

    /** Tells the ear whether the app's own speech is playing, on change. */
    fun reportOutput(model: LoopModel) {
        if (model.outputActive == outputHint) return
        outputHint = model.outputActive
        wiring.engines.ear.setOutputActive(outputHint)
    }

    private fun startIfWanted(model: LoopModel): LoopInput? {
        if (!model.micWanted) return null
        val current = ++generation
        job = wiring.scope.launch { listen(current) }
        return null
    }

    /** Stops [running] (once) and waits, bounded, for it to let go; it stays held while it has not. */
    private suspend fun release(running: Job): LoopInput? {
        if (!stopping) {
            stopping = true
            generation++
            running.cancel()
        }
        withTimeoutOrNull(wiring.config.releaseTimeout) { running.join() } ?: return wiring.unresponsive(VoicePort.EAR)
        job = null
        stopping = false
        return null
    }

    private suspend fun listen(current: Long) {
        val outcome = runCatching { wiring.engines.ear.listen().collect { wiring.inputs.send(LoopInput.FromEar(it, current)) } }
        val error = outcome.exceptionOrNull()
        if (error is CancellationException) throw error
        val ended = VoiceEarEvent.Failed(VoiceError(VoiceErrorCode.ENGINE_FAILED, VoicePort.EAR))
        wiring.inputs.send(LoopInput.FromEar(ended, current))
    }
}
