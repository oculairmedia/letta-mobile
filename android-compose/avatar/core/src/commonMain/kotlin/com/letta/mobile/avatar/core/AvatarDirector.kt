package com.letta.mobile.avatar.core

import kotlin.random.Random

/**
 * What the agent is doing right now, as far as the avatar cares.
 *
 * This is the original 5-value imperative input the app has always driven
 * ([AvatarDirector.setActivity]); it is a *subset* of the full [AvatarState]
 * vocabulary the director now arbitrates (PRD §4 P2, 12 states). It is retained
 * unchanged so existing consumers (`:desktop`, renderer tests) keep compiling.
 * New surfaces should prefer the explicit signals ([AvatarDirector.setUserTyping],
 * [AvatarDirector.setAwaitingApproval], [AvatarDirector.notifyTaskSucceeded], …)
 * and observe [AvatarState] via [AvatarDirector.addStateListener].
 */
enum class AvatarActivity {
    /** Nothing happening — neutral face, occasional blinks. */
    IDLE,

    /** The user is talking / composing — attentive, still. */
    LISTENING,

    /** The agent is reasoning or running tools — relaxed, inward. */
    THINKING,

    /** The agent's reply is streaming/being spoken — mouth animates. */
    SPEAKING,

    /** Something went wrong — brief sad face, then settles. */
    ERROR,
}

/** Observes director [AvatarState] enter/exit transitions (PRD §4 P2: "observable by mods"). */
fun interface AvatarStateListener {
    /**
     * Fired on every effective state change. The director always emits [exit]
     * of the old state *before* [enter] of the new one, so a listener that
     * tears down old-state chrome then sets up new-state chrome never overlaps.
     */
    fun onStateTransition(exit: AvatarState, enter: AvatarState)
}

/**
 * Behavior policy: turns agent activity, lifecycle status and explicit presence
 * signals into (a) an observable [AvatarState] (PRD §4 P2, 12 states) and (b)
 * the [AvatarRuntime] command stream per the §6 state matrix. This is
 * deliberately the ONLY place avatar behavior lives — renderers are mechanism,
 * so every renderer (web three-vrm today, filament-vrm later) inherits
 * identical life.
 *
 * The director owns *what* and *when* (the seconds in §6 are encoded as named
 * constants in [Config]); the renderer owns the animation curves.
 *
 * Drive it from the app with either the legacy activity input or the explicit
 * signals (they compose — the arbitrated [state] is the highest-priority active
 * candidate):
 * - [setActivity] — the original 5-value input, still fully supported;
 * - [setUserTyping] → [AvatarState.LISTENING];
 * - [setAwaitingApproval] → [AvatarState.WAITING_INPUT];
 * - [setDragged] → [AvatarState.DRAGGED];
 * - [notifyTaskSucceeded] → [AvatarState.SUCCESS] (self-expires per §6);
 * - [setQuietHours] → [AvatarState.SLEEPING];
 * - [setLifecycle] — avatar-asset load status → LOADING/FAILED/DEGRADED;
 * - [setSpeechLevel] with audio amplitude while TTS plays (without it, SPEAKING
 *   falls back to procedural mouth chatter, e.g. for streamed text);
 * - [flashEmotion] for one-off emotion cues (tags in agent output);
 * - [setLookTarget] to pass through pointer/camera attention;
 * - [tick] every frame (or timer step) with the elapsed seconds.
 *
 * Observe transitions with [addStateListener] (mods, presence surfaces).
 *
 * Determinism: all randomness comes from the injected [random]; all timing from
 * [tick]'s deltas. Tests drive it with a seeded Random and fixed steps.
 */
class AvatarDirector(
    private val runtime: AvatarRuntime,
    private val random: Random = Random.Default,
    private val config: Config = Config(),
) {
    /**
     * Tunables, in seconds/weights. Defaults encode the §6 state-matrix timings
     * verbatim; the state matrix's numbers ARE the contract.
     */
    data class Config(
        // --- idle blink -------------------------------------------------------
        val blinkMinInterval: Float = 2.5f,
        val blinkMaxInterval: Float = 6.5f,
        val blinkCloseSeconds: Float = 0.09f,
        // --- mouth ------------------------------------------------------------
        /**
         * Mouth attack/release smoothing rates (per second). Tuned so the
         * open→close settle time matches SPEAKING visemes: 60ms attack /
         * 90ms release (§6 SPEAKING).
         */
        val mouthAttackRate: Float = 1f / 0.060f,
        val mouthReleaseRate: Float = 1f / 0.090f,
        /** How long a [flashEmotion]/legacy-error cue holds before decaying. */
        val emotionHoldSeconds: Float = 2.2f,
        val emotionDecaySeconds: Float = 0.9f,
        /** How long a [setSpeechLevel] sample stays authoritative. */
        val speechLevelFreshSeconds: Float = 0.25f,
        // --- §6 behavior-state timings (the contract) -------------------------
        /** LISTENING lean-in: released this long after typing stops (§6). */
        val listeningReleaseSeconds: Float = 0.8f,
        /** THINKING relaxed 0.6wt, attack 0.4s (§6). */
        val thinkingWeight: Float = 0.6f,
        val thinkingAttackSeconds: Float = 0.4f,
        /** WAITING_INPUT surprised 0.3wt (§6). */
        val waitingWeight: Float = 0.3f,
        /** LISTENING lean-in 0.3wt (§6). */
        val listeningWeight: Float = 0.3f,
        /** DRAGGED surprised 0.2wt; drop settle 0.4s (§6). */
        val draggedWeight: Float = 0.2f,
        val draggedSettleSeconds: Float = 0.4f,
        /** SUCCESS happy 0.7wt hold 1.5s (§6); self-expires after this total. */
        val successWeight: Float = 0.7f,
        val successHoldSeconds: Float = 1.5f,
        val successDecaySeconds: Float = 0.5f,
        /** ERROR sad 0.8wt hold 1.2s, decay 0.4s (§6). */
        val errorWeight: Float = 0.8f,
        val errorHoldSeconds: Float = 1.2f,
        val errorDecaySeconds: Float = 0.4f,
        /** SLEEPING holds the blink (eyes closed) at full weight (§6). */
        val sleepingBlinkWeight: Float = 1.0f,
    ) {
        /** SUCCESS state self-expiry (§6): the full happy moment, hold + decay. */
        val successTotalSeconds: Float get() = successHoldSeconds + successDecaySeconds
    }

    /**
     * The arbitrated presence state (PRD §4 P2). This is the single value every
     * surface and mod should read; it reflects priority arbitration across all
     * inputs, not any one signal.
     */
    var state: AvatarState = AvatarState.IDLE
        private set

    // --- input signals (candidates for arbitration) ---------------------------
    private var lifecycle: AvatarLifecycle = AvatarLifecycle.NONE
    private var dragged = false
    private var awaitingApproval = false
    private var quietHours = false
    private var userTyping = false
    private var typingReleaseRemaining = 0f
    /** The activity fed via the legacy [setActivity] input (THINKING/SPEAKING/ERROR/IDLE/LISTENING). */
    private var legacyActivity: AvatarActivity = AvatarActivity.IDLE
    /** SUCCESS is a momentary self-expiring signal, counted down in [tick]. */
    private var successRemaining = 0f
    /** ERROR from [setActivity]/[notifyError]: latched until a non-error legacy activity or success clears it. */
    private var errorLatched = false

    private val listeners = mutableListOf<AvatarStateListener>()

    // --- blink ----------------------------------------------------------------
    private var blinkCountdown = 0f
    private var blinkCloseRemaining = 0f
    private var blinkScheduled = false

    // --- mouth ----------------------------------------------------------------
    private var mouthCurrent = 0f
    private var mouthSent = 0f
    private var speechLevel = 0f
    private var speechLevelAge = Float.MAX_VALUE
    private var chatterRemaining = 0f
    private var chatterTarget = 0f

    // --- one-off emotion flash (legacy flashEmotion / cues) -------------------
    private var emotion: AvatarExpression? = null
    private var emotionWeight = 0f
    private var emotionHoldRemaining = 0f
    private var emotionDecayRemaining = 0f
    /** The decay's own total (SUCCESS/ERROR set their own per §6); the fraction is relative to this. */
    private var emotionDecayTotal = 0f

    // --- per-state base expression, cross-faded on transition -----------------
    private var stateExpression: AvatarExpression? = null
    private var stateExpressionTarget = 0f
    private var stateExpressionAttackSeconds = 0f
    /** Ramp progress 0..1 while attacking toward [stateExpressionTarget]. */
    private var stateExpressionAttack = 0f

    // ==========================================================================
    // Observation
    // ==========================================================================

    /** Register [listener] for [AvatarState] enter/exit transitions. */
    fun addStateListener(listener: AvatarStateListener) {
        listeners.add(listener)
    }

    /** Remove a previously-registered [listener]. */
    fun removeStateListener(listener: AvatarStateListener) {
        listeners.remove(listener)
    }

    // ==========================================================================
    // Input signals
    // ==========================================================================

    /**
     * Legacy 5-value input, unchanged. Maps onto the arbitrated state machine:
     * THINKING/SPEAKING/LISTENING set the legacy activity; ERROR latches an
     * error cue; any other activity clears it. Explicit signals ([setDragged],
     * [setAwaitingApproval], …) still take priority per §4 P2 when active.
     *
     * Clearing on every non-ERROR activity (not just IDLE) preserves the
     * pre-P2 `setActivity` contract, where any subsequent activity replaced
     * ERROR — so a caller that recovers by sending SPEAKING/THINKING/LISTENING
     * without first sending IDLE is not stuck red. (The [notifyError] latch is
     * for the explicit signal, which has no legacy-activity backing.)
     */
    fun setActivity(next: AvatarActivity) {
        legacyActivity = next
        errorLatched = next == AvatarActivity.ERROR
        // LISTENING can arrive via this legacy input too; keep typing-derived
        // LISTENING independent so setUserTyping(false) doesn't cancel it.
        arbitrate()
    }

    /** User is composing/typing → [AvatarState.LISTENING]; releases [Config.listeningReleaseSeconds] after it stops (§6). */
    fun setUserTyping(typing: Boolean) {
        if (typing) {
            userTyping = true
            typingReleaseRemaining = 0f
        } else if (userTyping) {
            // Typing stopped: clear the active flag and start the release timer
            // so LISTENING lingers exactly [Config.listeningReleaseSeconds] (§6)
            // before falling back. Leaving userTyping set would pin LISTENING
            // forever, since the countdown in tick is gated on !userTyping.
            userTyping = false
            typingReleaseRemaining = config.listeningReleaseSeconds
        }
        arbitrate()
    }

    /** A tool approval is pending → [AvatarState.WAITING_INPUT] (§6: steady amber, surprised 0.3wt). */
    fun setAwaitingApproval(awaiting: Boolean) {
        awaitingApproval = awaiting
        arbitrate()
    }

    /** The pet is being dragged → [AvatarState.DRAGGED] (top priority, bypasses SLEEPING). */
    fun setDragged(isDragged: Boolean) {
        dragged = isDragged
        arbitrate()
    }

    /** A task completed → momentary [AvatarState.SUCCESS] that self-expires after [Config.successTotalSeconds] (§6). */
    fun notifyTaskSucceeded() {
        val wasSuccess = state == AvatarState.SUCCESS
        successRemaining = config.successTotalSeconds
        errorLatched = false // success supersedes a stale error cue
        arbitrate()
        // If we were already SUCCESS, arbitrate()'s same-state guard skipped the
        // enter side effects — but this is a genuinely new completion event, so
        // replay the happy flash/presence pulse. Without this, back-to-back task
        // completions inside the 2s window are invisible (the earlier flash has
        // already decayed) even though the timer was extended.
        if (wasSuccess && state == AvatarState.SUCCESS) onEnter(AvatarState.SUCCESS)
    }

    /** Latch [AvatarState.ERROR] (sad flash → settle, §6). Cleared by [notifyTaskSucceeded] or `setActivity(IDLE)`. */
    fun notifyError() {
        errorLatched = true
        arbitrate()
    }

    /** Quiet hours → [AvatarState.SLEEPING], which gates all behavior except DRAGGED/ERROR (§4 P2, §6). */
    fun setQuietHours(quiet: Boolean) {
        quietHours = quiet
        arbitrate()
    }

    /**
     * Feed avatar-asset load status. A non-[AvatarLifecycle.NONE] value takes
     * over the observable [state] (LOADING/FAILED/DEGRADED) — the asset can't
     * express behavior while it isn't healthily loaded. The director issues no
     * behavior commands for these; their §6 columns are surface chrome (P4).
     */
    fun setLifecycle(next: AvatarLifecycle) {
        lifecycle = next
        arbitrate()
    }

    /** Audio amplitude 0..1 while speaking; stale samples fall back to chatter. */
    fun setSpeechLevel(level: Float) {
        speechLevel = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
        speechLevelAge = 0f
    }

    /** Flash [expression] at [weight], hold, then decay back to the baseline. */
    fun flashEmotion(expression: AvatarExpression, weight: Float = 1f) {
        // A new emotion replaces the previous one immediately.
        emotion?.takeIf { it != expression }?.let { runtime.setExpression(it, 0f) }
        emotion = expression
        emotionWeight = weight.coerceIn(0f, 1f)
        emotionHoldRemaining = config.emotionHoldSeconds
        emotionDecayRemaining = config.emotionDecaySeconds
        emotionDecayTotal = config.emotionDecaySeconds
        runtime.setExpression(expression, emotionWeight)
    }

    /** Pass-through attention target (pointer, camera, speaker position). */
    fun setLookTarget(target: AvatarLookTarget?) {
        runtime.setLookTarget(target)
    }

    // ==========================================================================
    // Arbitration (PRD §4 P2 priority)
    // ==========================================================================

    /**
     * Recompute the winning [AvatarState] from all current signals and, if it
     * changed, run exit/enter side effects and notify listeners. Called after
     * every signal mutation and once per [tick] (for the timed signals).
     */
    private fun arbitrate() {
        val next = resolveState()
        if (next == state) return
        transitionTo(next)
    }

    private fun resolveState(): AvatarState {
        // Lifecycle overrides everything: a broken/loading asset has no behavior.
        when (lifecycle) {
            AvatarLifecycle.LOADING -> return AvatarState.LOADING
            AvatarLifecycle.FAILED -> return AvatarState.FAILED
            AvatarLifecycle.DEGRADED -> return AvatarState.DEGRADED
            AvatarLifecycle.NONE -> Unit
        }

        // DRAGGED and ERROR bypass SLEEPING gating (§4 P2).
        if (dragged) return AvatarState.DRAGGED
        val error = errorLatched || legacyActivity == AvatarActivity.ERROR
        if (error) return AvatarState.ERROR

        // SLEEPING gates every remaining behavior (§4 P2).
        if (quietHours) return AvatarState.SLEEPING

        // Remaining priority order: WAITING_INPUT > SPEAKING > SUCCESS >
        // THINKING > LISTENING > IDLE.
        if (awaitingApproval) return AvatarState.WAITING_INPUT
        if (legacyActivity == AvatarActivity.SPEAKING) return AvatarState.SPEAKING
        if (successRemaining > 0f) return AvatarState.SUCCESS
        if (legacyActivity == AvatarActivity.THINKING) return AvatarState.THINKING
        if (userTyping || typingReleaseRemaining > 0f ||
            legacyActivity == AvatarActivity.LISTENING
        ) {
            return AvatarState.LISTENING
        }
        return AvatarState.IDLE
    }

    private fun transitionTo(next: AvatarState) {
        val previous = state
        onExit(previous)
        state = next
        onEnter(next)
        // Exit-before-enter ordering is part of the observable contract.
        // Iterate a snapshot so a listener that (un)registers a listener during
        // its callback doesn't corrupt this loop's indices or throw
        // IndexOutOfBoundsException; the mutation takes effect next transition.
        for (listener in listeners.toList()) {
            listener.onStateTransition(previous, next)
        }
    }

    /** State-exit side effects: fade down the leaving state's base expression. */
    private fun onExit(leaving: AvatarState) {
        // Clear the departing state's base expression immediately so a new
        // state's expression cross-fades cleanly (renderer owns the 300ms curve).
        stateExpression?.let { runtime.setExpression(it, 0f) }
        stateExpression = null
        stateExpressionTarget = 0f
        stateExpressionAttackSeconds = 0f
    }

    /** State-enter side effects: install the entered state's §6 behavior. */
    private fun onEnter(entered: AvatarState) {
        when (entered) {
            AvatarState.THINKING ->
                installStateExpression(AvatarExpression.Relaxed, config.thinkingWeight, config.thinkingAttackSeconds)

            AvatarState.WAITING_INPUT ->
                installStateExpression(AvatarExpression.Surprised, config.waitingWeight, attackSeconds = 0f)

            AvatarState.LISTENING ->
                installStateExpression(AvatarExpression.Neutral, config.listeningWeight, attackSeconds = 0f)

            AvatarState.DRAGGED ->
                installStateExpression(AvatarExpression.Surprised, config.draggedWeight, attackSeconds = 0f)

            AvatarState.SPEAKING ->
                installStateExpression(AvatarExpression.Happy, 0.2f, attackSeconds = 0f)

            AvatarState.SUCCESS -> {
                // Happy 0.7wt hold 1.5s, then decays; state self-expires (tick).
                flashEmotion(AvatarExpression.Happy, config.successWeight)
                emotionHoldRemaining = config.successHoldSeconds
                emotionDecayRemaining = config.successDecaySeconds
                emotionDecayTotal = config.successDecaySeconds
            }

            AvatarState.ERROR -> {
                // Sad 0.8wt hold 1.2s decay 0.4s (§6).
                flashEmotion(AvatarExpression.Sad, config.errorWeight)
                emotionHoldRemaining = config.errorHoldSeconds
                emotionDecayRemaining = config.errorDecaySeconds
                emotionDecayTotal = config.errorDecaySeconds
            }

            AvatarState.SLEEPING -> {
                // Eyes closed: hold blink at full weight, suppress the blink loop.
                runtime.setExpression(BLINK, config.sleepingBlinkWeight)
            }

            // IDLE and the lifecycle states drive no director behavior.
            AvatarState.IDLE,
            AvatarState.LOADING,
            AvatarState.FAILED,
            AvatarState.DEGRADED,
            -> Unit
        }

        // Leaving SLEEPING must release the held-closed eyes.
        if (entered != AvatarState.SLEEPING) {
            // Only relevant if we were sleeping; harmless otherwise (blink loop
            // owns this key and will re-drive it).
            if (blinkCloseRemaining <= 0f) runtime.setExpression(BLINK, 0f)
        }
    }

    /** Install [expression] at [target], reached over [attackSeconds] (0 = immediate). */
    private fun installStateExpression(expression: AvatarExpression, target: Float, attackSeconds: Float) {
        stateExpression = expression
        stateExpressionTarget = target.coerceIn(0f, 1f)
        stateExpressionAttackSeconds = attackSeconds
        stateExpressionAttack = 0f
        if (attackSeconds <= 0f) {
            stateExpressionAttack = 1f
            runtime.setExpression(expression, stateExpressionTarget)
        } else {
            runtime.setExpression(expression, 0f) // ramp from 0 in tick
        }
    }

    // ==========================================================================
    // Time
    // ==========================================================================

    /** Advance behavior by [deltaSeconds]; also ticks the runtime. */
    fun tick(deltaSeconds: Float) {
        if (deltaSeconds <= 0f || deltaSeconds.isNaN()) return
        tickTimedSignals(deltaSeconds)
        tickStateExpression(deltaSeconds)
        tickBlink(deltaSeconds)
        tickMouth(deltaSeconds)
        tickEmotion(deltaSeconds)
        runtime.update(deltaSeconds)
    }

    /** Count down the self-expiring / lingering signals, then re-arbitrate. */
    private fun tickTimedSignals(delta: Float) {
        var changed = false
        if (successRemaining > 0f) {
            successRemaining -= delta
            if (successRemaining <= 0f) {
                successRemaining = 0f
                changed = true
            }
        }
        if (!userTyping && typingReleaseRemaining > 0f) {
            typingReleaseRemaining -= delta
            if (typingReleaseRemaining <= 0f) {
                typingReleaseRemaining = 0f
                changed = true
            }
        }
        if (changed) arbitrate()
    }

    /** Ramp the current state's base expression toward its target over its attack time. */
    private fun tickStateExpression(delta: Float) {
        val expression = stateExpression ?: return
        if (stateExpressionAttackSeconds <= 0f) return
        val step = delta / stateExpressionAttackSeconds
        stateExpressionAttack += step
        if (stateExpressionAttack >= 1f) {
            stateExpressionAttack = 1f
            stateExpressionAttackSeconds = 0f // reached target; stop ramping
        }
        runtime.setExpression(expression, stateExpressionTarget * stateExpressionAttack)
    }

    // --- blinking -------------------------------------------------------------

    private fun tickBlink(delta: Float) {
        // SLEEPING holds the eyes closed and suppresses the blink loop (§6).
        if (state == AvatarState.SLEEPING) return
        if (!blinkScheduled) {
            scheduleNextBlink()
        }
        if (blinkCloseRemaining > 0f) {
            blinkCloseRemaining -= delta
            if (blinkCloseRemaining <= 0f) {
                runtime.setExpression(BLINK, 0f)
            }
            return
        }
        blinkCountdown -= delta
        if (blinkCountdown <= 0f) {
            runtime.setExpression(BLINK, 1f)
            blinkCloseRemaining = config.blinkCloseSeconds
            scheduleNextBlink()
        }
    }

    private fun scheduleNextBlink() {
        blinkCountdown = config.blinkMinInterval +
            random.nextFloat() * (config.blinkMaxInterval - config.blinkMinInterval)
        blinkScheduled = true
    }

    // --- mouth ---------------------------------------------------------------

    private fun tickMouth(delta: Float) {
        speechLevelAge += delta
        val target = when {
            state != AvatarState.SPEAKING -> 0f
            speechLevelAge <= config.speechLevelFreshSeconds -> speechLevel
            else -> proceduralChatter(delta)
        }
        val rate = if (target > mouthCurrent) config.mouthAttackRate else config.mouthReleaseRate
        mouthCurrent += (target - mouthCurrent) * (rate * delta).coerceAtMost(1f)
        if (kotlin.math.abs(mouthCurrent - mouthSent) > 0.01f) {
            mouthSent = mouthCurrent
            runtime.setMouthOpen(mouthCurrent)
        }
    }

    /**
     * Syllable-ish mouth movement for SPEAKING without audio levels (streamed
     * text): hop between random openness targets at speech-like cadence.
     */
    private fun proceduralChatter(delta: Float): Float {
        chatterRemaining -= delta
        if (chatterRemaining <= 0f) {
            chatterRemaining = 0.06f + random.nextFloat() * 0.14f // 60–200ms
            chatterTarget = if (random.nextFloat() < 0.25f) 0f else 0.25f + random.nextFloat() * 0.6f
        }
        return chatterTarget
    }

    // --- emotion flashes -------------------------------------------------------

    private fun tickEmotion(delta: Float) {
        val active = emotion ?: return
        if (emotionHoldRemaining > 0f) {
            emotionHoldRemaining -= delta
            return
        }
        emotionDecayRemaining -= delta
        val total = if (emotionDecayTotal > 0f) emotionDecayTotal else config.emotionDecaySeconds
        val fraction = (emotionDecayRemaining / total).coerceIn(0f, 1f)
        val weight = emotionWeight * fraction
        runtime.setExpression(active, weight)
        if (fraction <= 0f) {
            emotion = null
        }
    }

    private companion object {
        /** VRM preset blink expression (both eyes). */
        val BLINK = AvatarExpression.Custom("blink")
    }
}
