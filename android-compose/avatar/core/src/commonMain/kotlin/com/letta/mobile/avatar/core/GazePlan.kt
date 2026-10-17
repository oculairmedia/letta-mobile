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
    AWAY("off to the side", "lost in thought: gaze parked off-axis, the way people think"),
    PEER("another agent", "the mascot next door"),

    // Attention points (letta-mobile-te3n1.2): places a surface published as worth a look.
    CARET("the caret", "following what you type, letter by letter"),
    MESSAGE("the newest reply", "reading the message as it lands"),
    APPROVAL("the approval card", "it is asking you something"),
    TOOL("the running tool", "watching its work run"),
}

/** One justified look: the target, how likely, how long it holds, and the pause before the next. */
data class GazeLook(
    val target: GazeTarget,
    val weight: Int,
    val dwellSeconds: ClosedFloatingPointRange<Float>,
    val gapSeconds: ClosedFloatingPointRange<Float> = 0.5f..2.5f,
)

/**
 * Per-state justified attention. Numbers began as the bench's `GAZE_PLAN` (ms) converted to
 * seconds; the at-work rows (THINKING / WORKING / SPEAKING) are product-tuned to follow the content
 * (letta-mobile-bglj6.1) and no longer match the spike.
 */
object GazePlan {
    val byState: Map<AvatarState, List<GazeLook>> = mapOf(
        // Straight ahead is the exception, not the rest pose: people park their gaze off-axis
        // while they think, glance at whoever is next to them, and only meet your eyes to
        // address you. AWAY and PEER carry most of the idle time; OWN itself drifts aside.
        AvatarState.IDLE to listOf(
            GazeLook(GazeTarget.AWAY, 35, 3f..8f),
            GazeLook(GazeTarget.OWN, 25, 3f..7f),
            GazeLook(GazeTarget.PEER, 15, 2f..5f),
            GazeLook(GazeTarget.USER, 15, 1.5f..3.5f),
            GazeLook(GazeTarget.CURSOR, 10, 1.5f..3f),
        ),
        // Typing: the eyes ride the caret (te3n1.2); the field itself is the fallback while the
        // composer publishes no caret, and its scan follows the caret's x when it does.
        AvatarState.LISTENING to listOf(
            GazeLook(GazeTarget.CARET, 50, 3f..8f, 0.3f..1.2f),
            GazeLook(GazeTarget.INPUT, 10, 3f..8f, 0.3f..1.2f),
            GazeLook(GazeTarget.USER, 15, 1f..2.5f),
            GazeLook(GazeTarget.AWAY, 10, 1.5f..3f),
            GazeLook(GazeTarget.CURSOR, 10, 1f..2f),
            GazeLook(GazeTarget.PEER, 5, 1f..2.5f),
        ),
        // At work (thinking, running tools, streaming) the eyes belong on the content being written:
        // the timeline's live edge, up and to the right of the companion (letta-mobile-bglj6.1 device
        // feedback - the old plan spent 65 % of thinking parked aside, half of it to the left). The
        // asides that remain go right and up (ContentGaze.aside). Short gaps keep the look on the work.
        // THINKING (te3n1.2, wbin4.8 gaze half): its asides go up and right and hold longer, the
        // way a person looks up while they think.
        AvatarState.THINKING to listOf(
            GazeLook(GazeTarget.TIMELINE, 60, 3f..7f, 0.3f..1.2f),
            GazeLook(GazeTarget.AWAY, 20, 3f..7f),
            GazeLook(GazeTarget.OWN, 15, 2f..5f),
            GazeLook(GazeTarget.PEER, 5, 1.5f..3f),
        ),
        // WORKING looks at its work: the running tool's row, then the timeline where its output lands.
        AvatarState.WORKING to listOf(
            GazeLook(GazeTarget.TOOL, 55, 3f..7f, 0.3f..1.2f),
            GazeLook(GazeTarget.TIMELINE, 25, 3f..7f, 0.3f..1.2f),
            GazeLook(GazeTarget.AWAY, 10, 2f..5f),
            GazeLook(GazeTarget.OWN, 7, 2f..5f),
            GazeLook(GazeTarget.PEER, 3, 1.5f..3f),
        ),
        // SPEAKING streams the reply: it reads the newest message's live edge, glancing at you now and then.
        AvatarState.SPEAKING to listOf(
            GazeLook(GazeTarget.MESSAGE, 45, 3f..7f, 0.3f..1.2f),
            GazeLook(GazeTarget.TIMELINE, 20, 3f..7f, 0.3f..1.2f),
            GazeLook(GazeTarget.USER, 20, 1.5f..3.5f, 0.3f..1.5f),
            GazeLook(GazeTarget.AWAY, 8, 1.5f..3.5f),
            GazeLook(GazeTarget.CURSOR, 5, 1f..2f),
            GazeLook(GazeTarget.PEER, 2, 1f..2.5f),
        ),
        // Asking you: the card that asks, then you. Without a card the old 70 / 20 / 10 split holds.
        AvatarState.WAITING_INPUT to listOf(
            GazeLook(GazeTarget.APPROVAL, 70, 3f..6f, 0.3f..1f),
            GazeLook(GazeTarget.USER, 20, 4f..9f, 0.3f..1f),
            GazeLook(GazeTarget.CURSOR, 7, 1.5f..3f),
            GazeLook(GazeTarget.AWAY, 3, 1.5f..3f),
        ),
        AvatarState.DRAGGED to listOf(
            GazeLook(GazeTarget.CURSOR, 100, 10f..10f, 0f..0f),
        ),
        AvatarState.SUCCESS to listOf(
            GazeLook(GazeTarget.USER, 100, 3f..3f, 0f..0f),
        ),
        AvatarState.ERROR to listOf(
            GazeLook(GazeTarget.AWAY, 40, 3f..7f),
            GazeLook(GazeTarget.OWN, 30, 3f..7f),
            GazeLook(GazeTarget.USER, 30, 1.5f..3f),
        ),
        AvatarState.SLEEPING to listOf(
            GazeLook(GazeTarget.OWN, 100, 60f..60f),
        ),
        // A dream is sleep with the agent busy inside it: the same held, inward look.
        AvatarState.DREAMING to listOf(
            GazeLook(GazeTarget.OWN, 100, 60f..60f),
        ),
    )

    /** Spike fallback when the state is not in `GAZE_PLAN` (lifecycle states). */
    val fallback: List<GazeLook> = listOf(GazeLook(GazeTarget.OWN, 1, 60f..60f))

    fun forState(state: AvatarState): List<GazeLook> = byState[state] ?: fallback
}
