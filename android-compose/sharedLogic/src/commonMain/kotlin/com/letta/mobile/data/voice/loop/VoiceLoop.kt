package com.letta.mobile.data.voice.loop

import com.letta.mobile.data.voice.InterruptStatus
import com.letta.mobile.data.voice.VoiceLoopConfig
import com.letta.mobile.data.voice.activation.KeyDecision
import com.letta.mobile.data.voice.activation.VoiceActivationEvent
import com.letta.mobile.data.voice.activation.VoiceActivationKind
import com.letta.mobile.data.voice.activation.VoiceActivationSource
import com.letta.mobile.data.voice.loop.machine.LoopInput
import com.letta.mobile.data.voice.loop.machine.LoopModel
import com.letta.mobile.data.voice.loop.machine.VoiceLoopMachine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/**
 * The hands-free voice loop: listen, send the utterance, speak the answer, listen again.
 *
 * Phases: IDLE -> LISTENING -> THINKING (utterance sent, no reply text yet) -> SPEAKING -> LISTENING
 * (continuous) or IDLE (single shot). Speech while the app is thinking or speaking interrupts it
 * (barge-in, when enabled). [stop] works in every phase. Failures settle in FAILED with a tagged
 * error; activating again starts over.
 *
 * Rules the loop guarantees:
 * - Every turn carries a generation token. Reply text from an older, interrupted or stopped turn,
 *   ear events from a closed ear, and anything from before [conversationChanged] are dropped.
 * - A phase is published only after the engines have actually let go: [stop] waits (bounded by
 *   [VoiceLoopConfig.releaseTimeout]) for the mouth, the ear and every answer to settle; an engine
 *   or answer that will not let go turns into an UNRESPONSIVE failure. An ear that timed out stays
 *   held, and no second ear opens until it has released. [shutdown] awaits all of this.
 * - Interrupting an answer is a request that can fail: while it is outstanding the state's
 *   [InterruptStatus] is REQUESTED (or UNRESPONSIVE) and that answer's text is never spoken.
 * - The microphone is never opened over the app's own speech: the mouth is stopped (and its stop
 *   awaited) before the ear opens, and a step that starts speech without barge-in closes the ear
 *   first; the ear stays open during speech only while barge-in keeps it open on purpose. A mouth
 *   that will not stop keeps the ear closed. The ear is told when output is playing
 *   ([com.letta.mobile.data.voice.engine.VoiceEar.setOutputActive]).
 * - Mouth failures name their segment; a failure for output already stopped is ignored.
 * - End of turn is the ear's decision; the loop has no silence timer. The only timer is the
 *   thinking timeout from sending to the answer's first text (not its first whole sentence).
 * - A capture retry is handed to the ear once it reports listening; an ear that no longer holds
 *   the capture fails with CAPTURE_EXPIRED.
 * - A late reply (text from an answer that delegated and went quiet) is spoken when the loop is
 *   listening and the person is not mid-utterance, or idle after a single shot; otherwise it is
 *   held (bounded) and spoken after the current answer. With the DISCARD policy it is never spoken.
 *
 * Every public method only enqueues; one coroutine on [VoiceLoopRuntime.dispatcher] applies inputs
 * in order.
 */
class VoiceLoop(
    engines: VoiceEngines,
    config: VoiceLoopConfig,
    private val runtime: VoiceLoopRuntime,
) {
    private val job = SupervisorJob(runtime.scope.coroutineContext[Job])
    private val scope = runtime.scope + job + runtime.dispatcher
    private val inputs = Channel<LoopInput>(Channel.UNLIMITED)
    private val runner = LoopEffectRunner(LoopWiring(engines, config, scope, inputs))
    private var model = LoopModel(config)
    private val mutableState = MutableStateFlow(VoiceLoopState(phaseSince = runtime.clock.now()))

    /** The loop's state, for a page and the avatar. */
    val state: StateFlow<VoiceLoopState> = mutableState.asStateFlow()

    init {
        scope.launch { for (input in inputs) process(input) }
        scope.launch { engines.mouth.events.collect { inputs.send(LoopInput.FromMouth(it)) } }
    }

    /** Starts listening, or interrupts the answer being awaited or spoken. */
    fun activate(kind: VoiceActivationKind) {
        inputs.trySend(LoopInput.Activate(kind))
    }

    /** The person finished their turn (push-to-talk release); the ear decides what was said. */
    fun endTurn() {
        inputs.trySend(LoopInput.EndTurn)
    }

    /** Stops everything and goes idle once every engine has let go. */
    fun stop() {
        inputs.trySend(LoopInput.Stop())
    }

    /** The conversation or agent changed: drop every outstanding answer and late reply, and go idle. */
    fun conversationChanged() {
        inputs.trySend(LoopInput.Stop(contextChanged = true))
    }

    /** Retries the capture named by [VoiceLoopState.retryableCapture]. */
    fun retryCapture() {
        inputs.trySend(LoopInput.RetryCapture)
    }

    /** Applies a key classifier [decision] raised by [kind]. */
    fun onKey(decision: KeyDecision, kind: VoiceActivationKind) {
        when (decision) {
            KeyDecision.START -> activate(kind)
            KeyDecision.END_TURN -> endTurn()
            KeyDecision.DISCARD -> stop()
            KeyDecision.NONE -> Unit
        }
    }

    /** Follows [source] until the returned job is cancelled or the loop closes. */
    fun bind(source: VoiceActivationSource): Job = scope.launch {
        source.events.collect { inputs.send(it.toInput()) }
    }

    /**
     * Stops everything, waits until the stop has been applied and every engine has let go (each
     * wait bounded by the release budget), then ends the loop's coroutines. Prefer this to [close].
     */
    suspend fun shutdown() {
        val done = CompletableDeferred<Unit>()
        if (inputs.trySend(LoopInput.Stop(done = done)).isSuccess) {
            job.invokeOnCompletion { done.complete(Unit) }
            done.await()
        }
        close()
    }

    /** Ends the loop's coroutines at once, without releasing the engines; use [shutdown] for an orderly end. */
    fun close() {
        job.cancel()
        inputs.close()
    }

    private suspend fun process(first: LoopInput) {
        val pending = ArrayDeque(listOf(first))
        val acknowledgements = mutableListOf<CompletableDeferred<Unit>>()
        while (pending.isNotEmpty()) {
            val input = pending.removeFirst()
            if (runner.isStale(input)) continue
            (input as? LoopInput.Stop)?.done?.let(acknowledgements::add)
            val step = VoiceLoopMachine.reduce(model, input)
            model = step.model
            pending += runner.apply(step)
        }
        publish()
        acknowledgements.forEach { it.complete(Unit) }
    }

    private fun publish() {
        val previous = mutableState.value
        val since = if (previous.phase == model.phase) previous.phaseSince else runtime.clock.now()
        mutableState.value = LoopStateProjection.project(model, since)
    }

    private fun VoiceActivationEvent.toInput(): LoopInput = when (this) {
        is VoiceActivationEvent.Activated -> LoopInput.Activate(kind)
        is VoiceActivationEvent.EndTurn -> LoopInput.EndTurn
        is VoiceActivationEvent.Discard -> LoopInput.Stop()
        is VoiceActivationEvent.Degraded -> LoopInput.Notice(error)
    }
}
