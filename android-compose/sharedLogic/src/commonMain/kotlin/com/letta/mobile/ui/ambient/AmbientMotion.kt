package com.letta.mobile.ui.ambient

/**
 * Status → motion mapping for the ambient agent-status glow, shared by the
 * Android AGSL renderer and the desktop gradient renderer so the two cannot
 * drift apart.
 *
 * The point (Meridian's review): status must drive BEHAVIOR, not just tint.
 * The eye reads motion before hue — four tints of one identical animation
 * read as one animation. So each status gets its own speed, agitation, and
 * intensity envelope, and the renderers consume these exact floats for both
 * their shader/gradient path and any non-shader fallback (parity by
 * construction, not by parallel implementation).
 */
enum class AmbientMotionStatus { Idle, Running, Active, Failed, Completed }

/**
 * @property speed multiplier on the base breath rate (1 = the legacy 6s cycle)
 * @property agitation noise displacement multiplier (1 = legacy drift)
 * @property bloomEnvelope intensity at the moment the status lands
 * @property settledEnvelope intensity the status decays to and holds
 * @property settleMillis bloom→settled decay time; 0 for continuous states
 */
data class AmbientMotionSpec(
    val speed: Float,
    val agitation: Float,
    val bloomEnvelope: Float,
    val settledEnvelope: Float,
    val settleMillis: Int,
) {
    val isTransient: Boolean get() = bloomEnvelope != settledEnvelope
}

object AmbientMotion {
    /** The legacy full breath cycle the speed multiplier is relative to. */
    const val BASE_PERIOD_MILLIS: Int = 6000

    fun spec(status: AmbientMotionStatus): AmbientMotionSpec = when (status) {
        // Nearly still — presence, not activity. The envelope sits at the
        // afterglow level rather than full, and that is load-bearing: Idle is
        // where every transient status ENDS, and the tint fade out of
        // Completed/Failed takes ~600ms while the envelope retargets in 300ms.
        // An Idle envelope above the settled value it is fading FROM would
        // brighten the glow on its way out — the rebound this table exists to
        // prevent. Idle's envelope must not exceed any transient status's
        // settled envelope (asserted in AmbientMotionTest).
        AmbientMotionStatus.Idle -> AmbientMotionSpec(
            speed = 0.35f,
            agitation = 0.4f,
            bloomEnvelope = COMPLETED_AFTERGLOW,
            settledEnvelope = COMPLETED_AFTERGLOW,
            settleMillis = 0,
        )
        // Fast and noisy: visibly *working*.
        AmbientMotionStatus.Running -> AmbientMotionSpec(
            speed = 1.7f,
            agitation = 1.6f,
            bloomEnvelope = 1f,
            settledEnvelope = 1f,
            settleMillis = 0,
        )
        AmbientMotionStatus.Active -> AmbientMotionSpec(
            speed = 1f,
            agitation = 1f,
            bloomEnvelope = 1f,
            settledEnvelope = 1f,
            settleMillis = 0,
        )
        // Sharp, tight pulse that settles: fast breath, LOW noise (tight, not
        // frantic), a hard bloom that decays but holds above baseline —
        // failure stays visible until acted on.
        AmbientMotionStatus.Failed -> AmbientMotionSpec(
            speed = 2.4f,
            agitation = 0.7f,
            bloomEnvelope = 1.6f,
            settledEnvelope = 0.9f,
            settleMillis = 900,
        )
        // Transient state, transient animation: bloom then decay to a faint
        // afterglow instead of glowing forever at full intensity.
        AmbientMotionStatus.Completed -> AmbientMotionSpec(
            speed = 0.45f,
            agitation = 0.5f,
            bloomEnvelope = 1.5f,
            settledEnvelope = COMPLETED_AFTERGLOW,
            settleMillis = 2400,
        )
    }

    /** The faint glow a finished turn decays to, and where Idle sits. */
    private const val COMPLETED_AFTERGLOW = 0.3f

    /**
     * How long a host must HOLD a transient status before returning to Idle.
     *
     * Both chat hosts used a hard-coded 1400 ms, which cut Completed's 2400 ms
     * decay off at 58%: the envelope was still falling when Idle took over and
     * animated it back up, so the promised decay-to-afterglow read as a brief
     * intensity rebound. The hold has to come from the same table as the decay
     * it is waiting on, or the two drift by construction. It covers the rise
     * as well as the decay, for the same reason: a hold that expired mid-rise
     * would hand the envelope to Idle while it was still climbing.
     */
    fun holdMillis(status: AmbientMotionStatus): Int =
        if (spec(status).isTransient) ramp(current = 0f, status = status).totalMillis else 0

    /**
     * How a landing status moves the intensity envelope from where it already is.
     *
     * Hosts used to snap to [AmbientMotionSpec.bloomEnvelope] before starting the decay.
     * On a 120 Hz screen that is a flash: a finished turn arrives from Running at 1.0 and
     * the Completed bloom is 1.5, so the glow jumped half again brighter between two
     * frames and then spent 2.4s falling. Measured on device at turn end, the bottom band
     * of the timeline rose 51% in a single frame.
     *
     * Intensity therefore never steps. A bloom above the current glow is climbed over
     * [BLOOM_RISE_MILLIS]; a bloom at or below it is already a decay, so the settle starts
     * from where the glow actually is rather than dropping to meet the table.
     */
    fun ramp(current: Float, status: AmbientMotionStatus): AmbientEnvelopeRamp {
        val spec = spec(status)
        if (!spec.isTransient) {
            return AmbientEnvelopeRamp(
                riseMillis = 0,
                bloomEnvelope = spec.settledEnvelope,
                settleMillis = CONTINUOUS_SETTLE_MILLIS,
                settledEnvelope = spec.settledEnvelope,
            )
        }
        return AmbientEnvelopeRamp(
            riseMillis = if (current < spec.bloomEnvelope) BLOOM_RISE_MILLIS else 0,
            bloomEnvelope = spec.bloomEnvelope,
            settleMillis = spec.settleMillis,
            settledEnvelope = spec.settledEnvelope,
        )
    }

    /** Long enough that a 50% brightening reads as a swell rather than a flash. */
    const val BLOOM_RISE_MILLIS: Int = 200

    /**
     * Where the renderer wraps its integrated phase, in turns.
     *
     * The phase used to accumulate without bound, and a Float loses resolution as it
     * grows: after about a day of continuous animation its step reaches a frame's
     * advance, so frames land on the same value and then jump two at once. That is the
     * judder that only ever appeared in long-running sessions.
     *
     * 1024 is exactly representable and every frequency in the shader is an integer
     * multiple of 1/1024 turns, so the whole field is periodic here and the wrap is
     * seamless. The phase stays small enough that its resolution never decays: a frame
     * advances ~1/120 of a turn against a float step of ~6e-5 at this magnitude.
     */
    const val PHASE_WRAP_TURNS: Float = 1024f

    /**
     * How far the shader rotates its curated palette's hues toward the tint.
     *
     * The palette is an indigo→teal→gold cosine ramp that owes nothing to the theme, so
     * at low pull the glow shows olive and dusty pink smears belonging to no palette in
     * the app. Rotating hue (not replacing colour) keeps the field's internal variation
     * while making all of it adjacent to the theme's own hue.
     */
    const val PALETTE_HUE_PULL: Float = 0.6f

    /** Tone and chroma the ambient tint is built at, rather than a Material container role. */
    const val TINT_TONE_ON_DARK: Float = 30f
    const val TINT_TONE_ON_LIGHT: Float = 62f
    const val TINT_CHROMA: Float = 72f

    /** A continuous status has nothing to decay from, so it just eases to its level. */
    private const val CONTINUOUS_SETTLE_MILLIS = 300
}

/**
 * The two moves a host plays, in order, when a status lands: climb to the bloom, then
 * settle. Either may be absent - [risesFirst] is false when the glow is already at or
 * above the bloom, and a continuous status names its level in both fields.
 */
data class AmbientEnvelopeRamp(
    val riseMillis: Int,
    val bloomEnvelope: Float,
    val settleMillis: Int,
    val settledEnvelope: Float,
) {
    val risesFirst: Boolean get() = riseMillis > 0

    /** Total time before the envelope is at rest, which is what a host must hold for. */
    val totalMillis: Int get() = riseMillis + settleMillis
}
