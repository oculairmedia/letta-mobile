package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.loop.machine.LoopEffect
import com.letta.mobile.data.voice.loop.machine.LoopInput
import com.letta.mobile.data.voice.loop.machine.LoopModel
import com.letta.mobile.data.voice.loop.machine.ReplySignal
import com.letta.mobile.data.voice.loop.machine.Step
import com.letta.mobile.data.voice.loop.machine.TurnKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the runner's collaborators launch work and send follow-up inputs. */
internal class LoopWiring(
    val engines: VoiceEngines,
    val config: VoiceLoopConfig,
    val scope: CoroutineScope,
    val inputs: SendChannel<LoopInput>,
) {
    /** A fault for an engine on [port] that would not let go. */
    fun unresponsive(port: VoicePort): LoopInput = LoopInput.Fault(VoiceError(VoiceErrorCode.UNRESPONSIVE, port))

    /** Runs a non-suspending engine call on [port]; a throw becomes an ENGINE_FAILED fault, never a dead loop. */
    fun call(port: VoicePort, block: () -> Unit): LoopInput? {
        val error = runCatching(block).exceptionOrNull() ?: return null
        if (error is CancellationException) throw error
        return LoopInput.Fault(VoiceError(VoiceErrorCode.ENGINE_FAILED, port))
    }
}

/**
 * Runs the machine's effects against the engines. Called only from the loop's single processing
 * coroutine, so its fields need no locking. Every wait for an engine to let go is bounded by
 * [VoiceLoopConfig.releaseTimeout] and turns into an UNRESPONSIVE fault past it.
 */
internal class LoopEffectRunner(private val wiring: LoopWiring) {
    private val ear = EarSupervisor(wiring)
    private val mouth = MouthSupervisor(wiring)
    private val replies = ReplyFollower(wiring)
    private var timer: Job? = null
    private var timerTurn: TurnKey? = null

    /** True when [input] is an ear event from an ear that is no longer running. */
    fun isStale(input: LoopInput): Boolean = ear.isStale(input)

    /**
     * Applies [step] so the microphone and the app's speech never overlap unless barge-in keeps the
     * ear open on purpose: a step that no longer wants the microphone closes the ear first (before
     * any speech starts); a step that wants it runs the engine effects first (so the mouth has
     * stopped) and opens the ear only when they all succeeded and the mouth has confirmed any
     * earlier stop. Once a fault is recorded in a step, nothing more that starts output runs;
     * cleanup (stopping, cancelling) still does. Calls into the ear come last. Returns follow-up
     * inputs (faults), which the loop applies before publishing.
     */
    suspend fun apply(step: Step): List<LoopInput> {
        val model = step.model
        val faults = mutableListOf<LoopInput>()
        if (!model.micWanted) ear.reconcile(model)?.let(faults::add)
        step.effects.filterNot(ear::handles).forEach { effect ->
            if (faults.isEmpty() || !effect.startsOutput()) execute(effect)?.let(faults::add)
        }
        if (faults.isEmpty()) faults += openEar(step)
        ear.reportOutput(model)?.let(faults::add)
        reconcileTimer(model)
        return faults
    }

    private fun LoopEffect.startsOutput(): Boolean = this is LoopEffect.Speak || this is LoopEffect.FlushMouth

    private suspend fun openEar(step: Step): List<LoopInput> {
        if (!step.model.micWanted) return step.effects.filter(ear::handles).mapNotNull(ear::call)
        val opened = mouth.awaitRelease() ?: ear.reconcile(step.model)
        if (opened != null) return listOf(opened)
        return step.effects.filter(ear::handles).mapNotNull(ear::call)
    }

    private suspend fun execute(effect: LoopEffect): LoopInput? = when (effect) {
        is LoopEffect.Respond -> replies.respond(effect)
        is LoopEffect.RequestInterrupt -> replies.requestInterrupt(effect.turn)
        is LoopEffect.ReleaseReply -> replies.release(effect.turn)
        LoopEffect.CancelAllReplies -> replies.cancelAll()
        else -> mouthEffect(effect)
    }

    private suspend fun mouthEffect(effect: LoopEffect): LoopInput? = when (effect) {
        is LoopEffect.Speak -> mouth.speak(effect.segment)
        LoopEffect.FlushMouth -> mouth.flush()
        LoopEffect.StopMouth -> mouth.stop()
        else -> null
    }

    /** The first-text timer runs whenever an answer is open and has produced no text, in any phase. */
    private fun reconcileTimer(model: LoopModel) {
        val awaited = model.turn.active.takeIf { model.turn.replyOpen && !model.turn.textArrived }
        if (awaited == timerTurn) return
        timer?.cancel()
        timerTurn = awaited
        timer = awaited?.let { turn -> wiring.scope.launch { expire(turn) } }
    }

    private suspend fun expire(turn: TurnKey) {
        delay(wiring.config.thinkingTimeout)
        wiring.inputs.send(LoopInput.FromReply(turn, ReplySignal.ThinkingTimedOut))
    }
}
