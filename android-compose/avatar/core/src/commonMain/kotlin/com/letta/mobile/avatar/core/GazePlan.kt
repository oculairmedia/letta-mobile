package com.letta.mobile.avatar.core

/**
 * What the character can be looking at. Every look has one of these so a
 * viewer could name the reason. Lifted from the desktop bench
 * (`RiveDesktopSpike.kt`).
 *
 * [OWN] is "its own thoughts": the host writes centre gaze and the rig's
 * per-state default shows through (thinking up-left, error down, idle drifting).
 */
enum class GazeTarget(val label: String, val reason: String) {
    OWN("own thoughts", "the rig's default for this state"),
    USER("you", "addressing the person: straight at the camera"),
    CURSOR("the cursor", "your hand moved"),
    INPUT("the input", "watching you type"),
    TIMELINE("the timeline", "reading the code / its own reply"),
}

/** One justified look: the target, how likely, how long it holds, and the pause before the next. */
data class GazeLook(
    val target: GazeTarget,
    val weight: Int,
    val dwellSeconds: ClosedFloatingPointRange<Float>,
    val gapSeconds: ClosedFloatingPointRange<Float> = 0.5f..2.5f,
)

/**
 * Per-state justified attention. Numbers are the bench's `GAZE_PLAN` (ms)
 * converted to seconds — do not retune here without retuning the spike.
 */
object GazePlan {
    val byState: Map<AvatarState, List<GazeLook>> = mapOf(
        AvatarState.IDLE to listOf(
            GazeLook(GazeTarget.OWN, 50, 3f..7f),
            GazeLook(GazeTarget.USER, 25, 1.5f..3.5f),
            GazeLook(GazeTarget.CURSOR, 25, 1.5f..3f),
        ),
        AvatarState.LISTENING to listOf(
            GazeLook(GazeTarget.INPUT, 70, 3f..8f, 0.3f..1.2f),
            GazeLook(GazeTarget.USER, 20, 1f..2.5f),
            GazeLook(GazeTarget.CURSOR, 10, 1f..2f),
        ),
        AvatarState.THINKING to listOf(
            GazeLook(GazeTarget.OWN, 60, 3f..8f),
            GazeLook(GazeTarget.TIMELINE, 30, 2f..5f),
            GazeLook(GazeTarget.INPUT, 10, 1f..2.5f),
        ),
        AvatarState.SPEAKING to listOf(
            GazeLook(GazeTarget.USER, 55, 2.5f..6f, 0.3f..1.5f),
            GazeLook(GazeTarget.TIMELINE, 35, 1.5f..4f),
            GazeLook(GazeTarget.CURSOR, 10, 1f..2f),
        ),
        AvatarState.WAITING_INPUT to listOf(
            GazeLook(GazeTarget.USER, 80, 4f..9f, 0.3f..1f),
            GazeLook(GazeTarget.CURSOR, 20, 1.5f..3f),
        ),
        AvatarState.DRAGGED to listOf(
            GazeLook(GazeTarget.CURSOR, 100, 10f..10f, 0f..0f),
        ),
        AvatarState.SUCCESS to listOf(
            GazeLook(GazeTarget.USER, 100, 3f..3f, 0f..0f),
        ),
        AvatarState.ERROR to listOf(
            GazeLook(GazeTarget.OWN, 70, 3f..7f),
            GazeLook(GazeTarget.USER, 30, 1.5f..3f),
        ),
        AvatarState.SLEEPING to listOf(
            GazeLook(GazeTarget.OWN, 100, 60f..60f),
        ),
    )

    /** Spike fallback when the state is not in `GAZE_PLAN` (lifecycle states). */
    val fallback: List<GazeLook> = listOf(GazeLook(GazeTarget.OWN, 1, 60f..60f))

    fun forState(state: AvatarState): List<GazeLook> = byState[state] ?: fallback
}
