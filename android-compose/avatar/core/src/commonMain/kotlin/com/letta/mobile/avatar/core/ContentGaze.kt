package com.letta.mobile.avatar.core

/**
 * Where the mascot looks relative to the content it is writing (letta-mobile-bglj6.1, device
 * feedback: the companion kept looking down-left while the reply streamed in above and to the
 * right of it).
 *
 * The companion sits at the left of the thread, beside the prompt; the agent's reply is written
 * at the timeline's live edge, which is up and to the right of it. So while the agent is at work
 * the eyes go to that live edge, and no aside may take them to the left past [MAX_LEFT_X]. At rest
 * (idle, the user typing) the existing plan runs, but its wandering is biased toward the right /
 * up half-plane. Every function here is pure; [GazeDirector] applies them.
 *
 * Coordinates are gaze units ([GazePoint]): origin at the mascot, +x right, +y down.
 */
object ContentGaze {
    /** The furthest left (gaze units) the eyes may drift: a small angle, never a look away. */
    const val MAX_LEFT_X: Float = -0.12f


    /** Share of resting asides that go to the right of the mascot. */
    const val RIGHT_ASIDE_CHANCE: Float = 0.75f

    /** Share of resting asides that sit above the eye line. */
    const val UP_ASIDE_CHANCE: Float = 0.75f

    /**
     * Where the reply is being written inside the timeline [rect]: a reversed list grows from its
     * bottom, so the live edge is the lower band of the list, across its middle. [LIVE_EDGE_FRACTION]
     * of the height up from the bottom (capped at [LIVE_EDGE_MAX_PX]) is where the newest lines land.
     */
    fun liveEdgeOf(rect: GazeRect): GazePoint {
        val up = minOf(rect.height * LIVE_EDGE_FRACTION, LIVE_EDGE_MAX_PX)
        return GazePoint(rect.centerX, rect.bottom - up)
    }

    /** States in which the agent is producing content: thinking, running tools, streaming the reply. */
    fun isAtWork(state: AvatarState): Boolean =
        state == AvatarState.THINKING || state == AvatarState.WORKING || state == AvatarState.SPEAKING

    /**
     * The normalised look from a [companion] tile toward the timeline's [liveEdge] (both window
     * pixels) for [state]: null when the agent is not at work (the resting plan decides), otherwise
     * the soft-saturated look toward the live edge, held inside [constrain].
     */
    fun look(companion: GazeRect, liveEdge: GazePoint, state: AvatarState, minReachPx: Float): GazePoint? {
        if (!isAtWork(state)) return null
        if (companion.isEmpty) return null
        val raw = GazeMath.pointerToGaze(liveEdge.x, liveEdge.y, companion, minReachPx)
        return constrain(raw)
    }

    /**
     * Holds any planned look inside the allowed field: never left of [MAX_LEFT_X]. Vertical is left
     * to the geometry (a docked badge above the thread looks down at it). A look the user's pointer
     * demands is not constrained (the caller skips it): a hand that moved is a reason to look.
     */
    fun constrain(point: GazePoint): GazePoint =
        GazePoint(point.x.coerceIn(MAX_LEFT_X, 1f), point.y.coerceIn(-1f, 1f))

    /** Four uniform draws in 0..1 that place one aside: which side, how far, rise or dip, how high. */
    data class AsideDraws(val side: Float, val reach: Float, val up: Float, val height: Float)

    /**
     * An aside (AWAY / OWN dwell) from [draws]. At work it always goes right and up, toward the
     * content. At rest it goes right [RIGHT_ASIDE_CHANCE] of the time at [reach], otherwise only a
     * small step left (never past [MAX_LEFT_X]); it rises [UP_ASIDE_CHANCE] of the time, otherwise
     * dips a little.
     */
    fun aside(reach: ClosedFloatingPointRange<Float>, atWork: Boolean, draws: AsideDraws): GazePoint {
        val magnitude = reach.start + draws.reach * (reach.endInclusive - reach.start)
        val right = atWork || draws.side < RIGHT_ASIDE_CHANCE
        val x = if (right) magnitude else MAX_LEFT_X * draws.reach
        val up = atWork || draws.up < UP_ASIDE_CHANCE
        val y = if (up) -ASIDE_RISE * draws.height else ASIDE_DIP * draws.height
        return GazePoint(x, y)
    }

    private const val LIVE_EDGE_FRACTION = 0.2f
    private const val LIVE_EDGE_MAX_PX = 240f
    private const val ASIDE_RISE = 0.35f
    private const val ASIDE_DIP = 0.12f
}
