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
        // Nearly still — presence, not activity.
        AmbientMotionStatus.Idle -> AmbientMotionSpec(
            speed = 0.35f,
            agitation = 0.4f,
            bloomEnvelope = 1f,
            settledEnvelope = 1f,
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
            settledEnvelope = 0.3f,
            settleMillis = 2400,
        )
    }
}
