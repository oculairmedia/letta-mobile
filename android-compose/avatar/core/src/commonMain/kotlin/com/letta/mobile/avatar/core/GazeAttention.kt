package com.letta.mobile.avatar.core

import kotlin.jvm.JvmInline

/*
 * letta-mobile-te3n1.2 (absorbs letta-mobile-wbin4.9): parametric attention. Any surface can say
 * "this is worth a look" - the composer's caret, the newest reply, an approval card, a running
 * tool - and the gaze plan gains rows that aim at it. The host publishes window rects
 * ([AttentionRect]); [GazeWorld.fromWindow] turns them into gaze-unit [AttentionPoint]s, one per
 * kind; [GazeDirector] weighs each by how long it has been there ([AttentionInterest]).
 */

/** Which source an attention point is (a card's request id, a row's key), so a new one restarts its interest curve. */
@JvmInline
value class AttentionKey(val value: String)

/**
 * How strongly a point pulls the eyes as it ages: [peak] for [holdSeconds], then easing linearly to
 * [floor] (a fraction of [peak]) over [decaySeconds]. A zero [decaySeconds] pulls at [peak] forever.
 */
data class AttentionInterest(
    val peak: Float = 1f,
    val holdSeconds: Float = 0f,
    val decaySeconds: Float = 0f,
    val floor: Float = 1f,
) {
    /** The pull after [ageSeconds] on screen. */
    fun at(ageSeconds: Float): Float {
        if (decaySeconds <= 0f || ageSeconds <= holdSeconds) return peak
        val t = ((ageSeconds - holdSeconds) / decaySeconds).coerceIn(0f, 1f)
        return peak * (1f + (floor - 1f) * t)
    }

    companion object {
        /** Always worth its full weight while it is there (the caret, a running tool). */
        val STEADY = AttentionInterest()

        /** A request that asks for you: 1 -> 0.4 over 4 s, so a card left waiting stops owning the eyes. */
        val APPROVAL = AttentionInterest(decaySeconds = 4f, floor = 0.4f)

        /** The newest reply: full for 3 s, then easing to 0.3 over 3 s. */
        val MESSAGE = AttentionInterest(holdSeconds = 3f, decaySeconds = 3f, floor = 0.3f)
    }
}

/** Where inside a published rect the eyes go. */
enum class AttentionAim {
    /** The rect's centre (a card, a tool row, the caret). */
    CENTER,

    /** Where new text lands: the lower band of the rect ([ContentGaze.liveEdgeOf]). */
    LIVE_EDGE,
}

/** What kind of thing an attention point is; each one is a [GazeTarget] the plan can name. */
enum class AttentionKind(val target: GazeTarget, val interest: AttentionInterest, val aim: AttentionAim) {
    /** The composer's text caret while you type. */
    CARET(GazeTarget.CARET, AttentionInterest.STEADY, AttentionAim.CENTER),

    /** The newest assistant row, read at its live edge as it streams. */
    MESSAGE(GazeTarget.MESSAGE, AttentionInterest.MESSAGE, AttentionAim.LIVE_EDGE),

    /** An approval card waiting on you. */
    APPROVAL(GazeTarget.APPROVAL, AttentionInterest.APPROVAL, AttentionAim.CENTER),

    /** The tool row that is running now. */
    TOOL(GazeTarget.TOOL, AttentionInterest.STEADY, AttentionAim.CENTER),
}

/** One window-space attention source, as a host publishes it (same pixel space as the mascot tile). */
data class AttentionRect(
    val key: AttentionKey,
    val kind: AttentionKind,
    val rect: GazeRect,
    val interest: AttentionInterest = kind.interest,
)

/** The same source in this mascot's gaze units, ready for [GazeDirector]. */
data class AttentionPoint(
    val key: AttentionKey,
    val kind: AttentionKind,
    val point: GazePoint,
    val interest: AttentionInterest = kind.interest,
)

/** The attention kind a plan target aims at, or null for the targets that are not attention points. */
internal fun GazeTarget.attentionKind(): AttentionKind? = when (this) {
    GazeTarget.CARET -> AttentionKind.CARET
    GazeTarget.MESSAGE -> AttentionKind.MESSAGE
    GazeTarget.APPROVAL -> AttentionKind.APPROVAL
    GazeTarget.TOOL -> AttentionKind.TOOL
    else -> null
}

/** The point of [kind] in [points], or null. An indexed loop: read every frame, it allocates nothing. */
internal fun List<AttentionPoint>.pointOf(kind: AttentionKind): AttentionPoint? {
    for (i in indices) {
        val point = this[i]
        if (point.kind == kind) return point
    }
    return null
}

/**
 * Window rects to gaze points against [mascot]: one point per kind, the lowest on screen when a
 * kind is published more than once (in the reversed timeline that is the newest). Empty rects drop.
 */
internal fun List<AttentionRect>.toGazePoints(mascot: GazeRect, minReachPx: Float): List<AttentionPoint> {
    if (isEmpty() || mascot.isEmpty) return emptyList()
    return AttentionKind.entries.mapNotNull { kind ->
        val newest = filter { it.kind == kind && !it.rect.isEmpty }.maxByOrNull { it.rect.bottom } ?: return@mapNotNull null
        val aim = when (kind.aim) {
            AttentionAim.CENTER -> GazePoint(newest.rect.centerX, newest.rect.centerY)
            AttentionAim.LIVE_EDGE -> ContentGaze.liveEdgeOf(newest.rect)
        }
        AttentionPoint(newest.key, kind, GazeMath.pointerToGaze(aim.x, aim.y, mascot, minReachPx), newest.interest)
    }
}

/**
 * How long each kind's current point has been on screen, so its [AttentionInterest] can decay. A
 * point with a new [AttentionKey] (the next approval card) starts again at zero. Allocation-free
 * per tick: keys are compared as their strings, ages live in a primitive array.
 */
internal class AttentionClock {
    private val age = FloatArray(KINDS.size)
    private val seen = arrayOfNulls<String>(KINDS.size)

    fun advance(points: List<AttentionPoint>, seconds: Float) {
        for (i in KINDS.indices) {
            val point = points.pointOf(KINDS[i])
            val key = if (point == null) null else point.key.value
            if (key != seen[i]) {
                seen[i] = key
                age[i] = 0f
            } else {
                age[i] += seconds
            }
        }
    }

    /** [point]'s pull now. */
    fun interestOf(point: AttentionPoint): Float = point.interest.at(age[point.kind.ordinal])

    private companion object {
        val KINDS = AttentionKind.entries
    }
}
