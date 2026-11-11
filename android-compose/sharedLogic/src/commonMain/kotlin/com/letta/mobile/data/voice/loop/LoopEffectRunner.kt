package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.VoiceError
import com.letta.mobile.data.voice.VoiceErrorCode
import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.VoicePhase
import com.letta.mobile.data.voice.VoicePort
import com.letta.mobile.data.voice.loop.machine.LoopEffect
import com.letta.mobile.data.voice.loop.machine.LoopInput
import com.letta.mobile.data.voice.loop.machine.LoopModel
import com.letta.mobile.data.voice.loop.machine.ReplySignal
import com.letta.mobile.data.voice.loop.machine.Step
import com.letta.mobile.data.voice.loop.machine.TurnKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Where the runner's collaborators launch work and send follow-up inputs. */
internal class LoopWiring(
    val engines: VoiceEngines,
    val config: VoiceLoopConfig,
    val scope: CoroutineScope,
    val inputs: SendChannel<LoopInput>,
) {
    /** A fault for an engine on [port] that would not let go. */
    fun unresponsive(port: VoicePort): LoopInput = LoopInput.Fault(VoiceError(VoiceErrorCode.UNRESPONSIVE, port))
}

/**
 * Runs the machine's effects against the engines. Called only from the loop's single processing
 * coroutine, so its fields need no locking. Every wait for an engine to let go is bounded by
 * [VoiceLoopConfig.releaseTimeout] and turns into an UNRESPONSIVE fault past it.
 */
internal class LoopEffectRunner(private val wiring: LoopWiring) {
    private val ear = EarSupervisor(wiring)
    private val replies = ReplyFollower(wiring)
    private var timer: Job? = null
    private var timerTurn: TurnKey? = null

    /** True when [input] is an ear event from an ear that is no longer running. */
    fun isStale(input: LoopInput): Boolean = ear.isStale(input)

    /**
     * Applies [step]: engine effects first (so the mouth has stopped before any microphone opens),
     * then the microphone, then calls into the running ear. Returns follow-up inputs (faults).
     */
    suspend fun apply(step: Step): List<LoopInput> {
        val faults = step.effects.filterNot(ear::handles).mapNotNull { execute(it) }.toMutableList()
        ear.reconcile(step.model)?.let(faults::add)
        step.effects.filter(ear::handles).forEach(ear::call)
        ear.reportOutput(step.model)
        reconcileTimer(step.model)
        return faults
    }

    private suspend fun execute(effect: LoopEffect): LoopInput? = when (effect) {
        is LoopEffect.Respond -> replies.respond(effect)
        is LoopEffect.RequestInterrupt -> replies.requestInterrupt(effect.turn)
        is LoopEffect.ReleaseReply -> replies.release(effect.turn)
        LoopEffect.CancelAllReplies -> replies.cancelAll()
        else -> mouthEffect(effect)
    }

    private suspend fun mouthEffect(effect: LoopEffect): LoopInput? = when (effect) {
        is LoopEffect.Speak -> {
            wiring.engines.mouth.speak(effect.segment)
            null
        }
        LoopEffect.FlushMouth -> {
            wiring.engines.mouth.flush()
            null
        }
        LoopEffect.StopMouth -> stopMouth()
        else -> null
    }

    private suspend fun stopMouth(): LoopInput? {
        val stopped = withTimeoutOrNull(wiring.config.releaseTimeout) { wiring.engines.mouth.stop() }
        return if (stopped == null) wiring.unresponsive(VoicePort.MOUTH) else null
    }

    private fun reconcileTimer(model: LoopModel) {
        val awaited = model.turn.active.takeIf { model.phase == VoicePhase.THINKING }
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
