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
     * it is waiting on, or the two drift by construction.
     */
    fun holdMillis(status: AmbientMotionStatus): Int = spec(status).settleMillis
}
