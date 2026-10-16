package com.letta.mobile.avatar.core

/**
 * What the app knows about one agent right now, as levels (letta-mobile-te3n1.1). The
 * [MascotDirector] takes a whole snapshot at a time and applies only what changed, so a host
 * can hand it the same snapshot every time anything about the agent moves.
 */
data class MascotSignals(
    /** What the agent's turn is doing. */
    val activity: AvatarActivity = AvatarActivity.IDLE,
    /** A tool approval is waiting on the user. */
    val awaitingApproval: Boolean = false,
    /** The user is composing to this agent. */
    val userTyping: Boolean = false,
    /** The last attempt failed and nothing has replaced it yet. */
    val error: Boolean = false,
    /** A background dream (reflection / memory upkeep) is running. */
    val dreaming: Boolean = false,
    /** The tool in flight, for reactions that name it. */
    val toolName: String? = null,
    /** The platform asked for reduced motion: reactions shrink to a blink. */
    val reduceMotion: Boolean = false,
    /** A pointer is in the window (desktop, web); touch-only hosts have none. */
    val pointerPresent: Boolean = false,
)

/**
 * A discrete moment the [MascotDirector] reacts to (letta-mobile-te3n1.1). Run moments come from
 * the run registry's edge counts.
 *
 * Gesture moments follow the epic's ownership rule (decided 2026-10-16, option A): the HOST
 * interprets pointer and touch input - hit-testing, classifying hover / poke / pet / drag /
 * double-click, measuring velocity and seat travel - and reports the result here; the director
 * writes the contract inputs and triggers (the host, not the file's listeners, becomes the writer
 * of hovered / dragged), and the `.riv` owns every animation. The interaction slice
 * (letta-mobile-te3n1.4) produces these on every platform; M3 reports seat travel as [Motion].
 */
sealed interface MascotEvent {
    /** The user sent a message. */
    data object Sent : MascotEvent

    /** A tool started running. */
    data class ToolStarted(val toolName: String?) : MascotEvent

    /** A tool returned. */
    data object ToolEnded : MascotEvent

    /** The turn asked the user to approve a tool. */
    data object ApprovalRaised : MascotEvent

    /** A run reached its terminal; [ok] is false for a cancel, which is not a success. */
    data class RunFinished(val ok: Boolean) : MascotEvent

    /** An attempt failed. */
    data object RunFailed : MascotEvent

    /** The user poked the mascot. */
    data object Poke : MascotEvent

    /** The user started or stopped petting the mascot. */
    data class Petting(val active: Boolean) : MascotEvent

    /** The user picked the mascot up. */
    data object DragStarted : MascotEvent

    /** The user put the mascot down; [impact] 0..1 is how hard it landed. */
    data class DragEnded(val impact: Float) : MascotEvent

    /**
     * The mascot's own velocity through the window, in mascot widths per second, as the host
     * measured it - while dragged, or while a seat hand-off carries it. Zero when it comes to rest.
     */
    data class Motion(val vx: Float, val vy: Float) : MascotEvent

    /** A double-click / double-tap; [trick] picks the easter egg. */
    data class DoubleTap(val trick: Int) : MascotEvent

    /** The pointer entered or left the mascot. */
    data class Hover(val hovered: Boolean) : MascotEvent
}

/**
 * What the [MascotDirector] decided this frame, read by surfaces that draw around the mascot.
 * One instance per director, rewritten in place every tick: reading it allocates nothing.
 */
class MascotFrame internal constructor() {
    /** The arbitrated sustained state. */
    var state: AvatarState = AvatarState.IDLE
        internal set

    /** Where the eyes and head are pointed. */
    var gaze: GazePose = GazePose.CENTER
        internal set

    /** How intently it listens, 0..1. */
    var listenLevel: Float = 0f
        internal set

    var hovered: Boolean = false
        internal set

    var petting: Boolean = false
        internal set

    var reduceMotion: Boolean = false
        internal set

    /** The host-measured velocity ([MascotEvent.Motion]), mascot widths per second. */
    var motionX: Float = 0f
        internal set

    var motionY: Float = 0f
        internal set

    private val reactions = IntArray(CAPACITY)

    /** How many reactions fired since the previous tick. */
    var reactionCount: Int = 0
        private set

    /** The [index]th reaction fired since the previous tick, in firing order. */
    fun reactionAt(index: Int): AvatarReaction {
        require(index in 0 until reactionCount) { "no reaction $index of $reactionCount" }
        return AvatarReaction.entries[reactions[index]]
    }

    /** Whether [reaction] fired since the previous tick. */
    fun fired(reaction: AvatarReaction): Boolean {
        for (i in 0 until reactionCount) if (reactions[i] == reaction.ordinal) return true
        return false
    }

    internal fun copyReactionsFrom(source: ReactionBuffer) {
        reactionCount = source.count
        for (i in 0 until reactionCount) reactions[i] = source.ordinalAt(i)
    }

    internal companion object {
        /** More than this many reactions between two frames is a storm; the rest are dropped. */
        const val CAPACITY: Int = 16
    }
}

/** The reactions fired since the last tick, in order; fixed size so firing never allocates. */
internal class ReactionBuffer {
    private val ordinals = IntArray(MascotFrame.CAPACITY)

    var count: Int = 0
        private set

    fun add(reaction: AvatarReaction) {
        if (count < ordinals.size) ordinals[count++] = reaction.ordinal
    }

    fun ordinalAt(index: Int): Int = ordinals[index]

    fun clear() {
        count = 0
    }
}
