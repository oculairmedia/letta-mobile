package com.letta.mobile.avatar.core

import kotlin.random.Random

/** What the agent is doing right now, as far as the avatar cares. */
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

/**
 * Behavior policy: turns agent activity and emotion cues into the
 * [AvatarRuntime] command stream. This is deliberately the ONLY place
 * avatar behavior lives — renderers are mechanism, so every renderer
 * (web three-vrm today, filament-vrm later) inherits identical life.
 *
 * Drive it from the app:
 * - [setActivity] whenever the agent's presence state changes;
 * - [setSpeechLevel] with the audio amplitude while TTS plays (without it,
 *   SPEAKING falls back to procedural mouth chatter, e.g. for streamed text);
 * - [flashEmotion] for emotion cues (tags in agent output, tool failures);
 * - [setLookTarget] to pass through pointer/camera attention;
 * - [tick] every frame (or timer step) with the elapsed seconds.
 *
 * Determinism: all randomness comes from the injected [random]; all timing
 * from [tick]'s deltas. Tests drive it with a seeded Random and fixed steps.
 */
class AvatarDirector(
    private val runtime: AvatarRuntime,
    private val random: Random = Random.Default,
    private val config: Config = Config(),
) {
    /** Tunables, in seconds/levels. Defaults feel natural at 30–60 fps. */
    data class Config(
        val blinkMinInterval: Float = 2.5f,
        val blinkMaxInterval: Float = 6.5f,
        val blinkCloseSeconds: Float = 0.09f,
        /** Mouth attack/release smoothing rates (per second). */
        val mouthAttackRate: Float = 18f,
        val mouthReleaseRate: Float = 8f,
        /** How long a [flashEmotion] holds at full weight before decaying. */
        val emotionHoldSeconds: Float = 2.2f,
        val emotionDecaySeconds: Float = 0.9f,
        /** How long a [setSpeechLevel] sample stays authoritative. */
        val speechLevelFreshSeconds: Float = 0.25f,
    )

    var activity: AvatarActivity = AvatarActivity.IDLE
        private set

    private var blinkCountdown = 0f
    private var blinkCloseRemaining = 0f
    private var blinkScheduled = false

    private var mouthCurrent = 0f
    private var mouthSent = 0f
    private var speechLevel = 0f
    private var speechLevelAge = Float.MAX_VALUE
    private var chatterRemaining = 0f
    private var chatterTarget = 0f

    private var emotion: AvatarExpression? = null
    private var emotionWeight = 0f
    private var emotionHoldRemaining = 0f
    private var emotionDecayRemaining = 0f

    private var activityExpression: AvatarExpression? = null

    fun setActivity(next: AvatarActivity) {
        if (next == activity) return
        activity = next

        // Swap the activity's base expression.
        val nextExpression = baseExpressionFor(next)
        if (activityExpression != nextExpression?.first) {
            activityExpression?.let { runtime.setExpression(it, 0f) }
            nextExpression?.let { (expression, weight) -> runtime.setExpression(expression, weight) }
            activityExpression = nextExpression?.first
        }

        if (next == AvatarActivity.ERROR) {
            flashEmotion(AvatarExpression.Sad)
        }
        if (next != AvatarActivity.SPEAKING) {
            speechLevelAge = Float.MAX_VALUE
        }
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
        runtime.setExpression(expression, emotionWeight)
    }

    /** Pass-through attention target (pointer, camera, speaker position). */
    fun setLookTarget(target: AvatarLookTarget?) {
        runtime.setLookTarget(target)
    }

    /** Advance behavior by [deltaSeconds]; also ticks the runtime. */
    fun tick(deltaSeconds: Float) {
        if (deltaSeconds <= 0f || deltaSeconds.isNaN()) return
        tickBlink(deltaSeconds)
        tickMouth(deltaSeconds)
        tickEmotion(deltaSeconds)
        runtime.update(deltaSeconds)
    }

    // --- blinking -------------------------------------------------------------

    private fun tickBlink(delta: Float) {
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
            activity != AvatarActivity.SPEAKING -> 0f
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
        val fraction = (emotionDecayRemaining / config.emotionDecaySeconds).coerceIn(0f, 1f)
        val weight = emotionWeight * fraction
        runtime.setExpression(active, weight)
        if (fraction <= 0f) {
            emotion = null
        }
    }

    private fun baseExpressionFor(activity: AvatarActivity): Pair<AvatarExpression, Float>? =
        when (activity) {
            AvatarActivity.IDLE -> null
            AvatarActivity.LISTENING -> AvatarExpression.Neutral to 0.3f
            AvatarActivity.THINKING -> AvatarExpression.Relaxed to 0.35f
            AvatarActivity.SPEAKING -> AvatarExpression.Happy to 0.2f
            AvatarActivity.ERROR -> null // the sad flash carries it
        }

    private companion object {
        /** VRM preset blink expression (both eyes). */
        val BLINK = AvatarExpression.Custom("blink")
    }
}
