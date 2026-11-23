package com.letta.mobile.data.home.greeting

/**
 * What one agent in the greeting's cast is doing, folded from its presence. The agent's own turn
 * always wins over play: a [busy] mascot only ever looks at things.
 */
enum class GreetingMood {
    IDLE,
    THINKING,
    WORKING,

    /** A tool approval is waiting on the user. */
    WAITING,
    ERROR,

    /** The agent cannot be reached. */
    OFFLINE,

    /** A background dream (memory upkeep) is running. */
    ASLEEP,
    ;

    /** The agent's turn is under way or stuck: it has its own thing to show. */
    val busy: Boolean get() = this == THINKING || this == WORKING || this == WAITING || this == ERROR

    /** Something went wrong that a neighbour would worry about. */
    val troubled: Boolean get() = this == ERROR || this == OFFLINE
}

/** The app's link to its backend, as the greeting reads it. */
enum class GreetingConnection {
    CONNECTED,
    CONNECTING,
    DEGRADED,
    UNCONFIGURED,
    ;

    /** The link is being made or has been lost: the cast huddles by the mark. */
    val unsettled: Boolean get() = this == CONNECTING || this == DEGRADED
}

/** Something a mascot can attend to. */
sealed interface GreetingTarget {
    /** The person looking at the screen. */
    data object User : GreetingTarget

    /** The Baken mark beside the greeting line. */
    data object Mark : GreetingTarget

    /** Another mascot in the cast. */
    data class Actor(val id: String) : GreetingTarget
}

/** One cast member: which agent, and what it is doing now. */
data class GreetingCastMember(val id: String, val mood: GreetingMood)

/** Everything that can change the greeting: agent, user and system state, and time. */
sealed interface GreetingInput {
    /** The cast and each member's mood; a mood that changed is a moment the neighbours notice. */
    data class Cast(val members: List<GreetingCastMember>) : GreetingInput

    /** A cast member's run ended; [ok] false is a cancel or a failure. */
    data class RunFinished(val id: String, val ok: Boolean) : GreetingInput

    /** The user opened Home ([returning] after being away). */
    data class Arrived(val returning: Boolean = false) : GreetingInput

    /** The pointer is over [target], or over nothing. */
    data class PointerOver(val target: GreetingTarget?) : GreetingInput

    /** The user tapped or clicked [target]. */
    data class Tapped(val target: GreetingTarget) : GreetingInput

    /** The user is typing in Home's composer. */
    data class Typing(val active: Boolean) : GreetingInput

    data class Connection(val connection: GreetingConnection) : GreetingInput

    /** Home is fetching its data. */
    data class Hydrating(val active: Boolean) : GreetingInput

    /** The platform asked for reduced motion. */
    data class ReducedMotion(val active: Boolean) : GreetingInput

    /** Time passed. The only clock the reducer has. */
    data class Tick(val seconds: Float) : GreetingInput

    /**
     * Nothing has happened for a while. One idle mascot, picked by the reducer's chance, turns to a
     * neighbour, so a resting lineup still lives without the host ticking it every frame.
     */
    data object Quiet : GreetingInput
}

/** What a mascot is doing in the greeting. Each is one reaction to one stimulus, never a sequence. */
enum class GreetingBehavior {
    /** Nothing to react to; the rig's own idle plays. */
    SETTLED,

    /** Looking at something that caught its eye. */
    NOTICING,

    /** Waving hello at the user or at a neighbour that waved. */
    GREETING,

    /** A neighbour's run finished well: a hop toward it. */
    CHEERING,

    /** A neighbour went wrong: leaning toward it, holding the look. */
    CONCERNED,

    /** A neighbour is busy: peeking at it. */
    CURIOUS,

    /** The connection is unsettled: drawn in toward the mark. */
    HUDDLED,
}

/** A one-shot the rig plays. */
enum class GreetingGesture {
    WAVE,
    CHEER,
}

/** Which stimulus a cue is; each kind maps to the behaviour it invites and how strongly. */
enum class GreetingCueKind {
    /** The user said hello: arrived, came back, or tapped a mascot. */
    USER_HELLO,
    POINTED,
    TAPPED,
    NEIGHBOUR_GREETED,
    NEIGHBOUR_DONE,
    NEIGHBOUR_CHEERED,
    NEIGHBOUR_TROUBLE,
    NEIGHBOUR_BUSY,
    MARK_CHANGED,
    USER_TYPING,

    /** A quiet moment: one mascot glances at a neighbour. */
    IDLE_GLANCE,
}

/**
 * A stimulus waiting for the next tick: what happened, where it came from, how strong it still is
 * (a reaction passed on to a neighbour is weaker), and whom it is for (null: everyone but [source]).
 */
data class GreetingCue(
    val kind: GreetingCueKind,
    val source: GreetingTarget,
    val salience: Float,
    val only: String? = null,
)

/**
 * One mascot's state: its [behavior] toward [target] for [remaining] more seconds, how strongly it
 * took hold ([score]), how interesting each target still is ([interest], absent means fresh), and
 * the one-shot counter the UI watches ([gestureSerial] goes up each time [gesture] should play).
 */
data class GreetingActor(
    val id: String,
    val mood: GreetingMood = GreetingMood.IDLE,
    val behavior: GreetingBehavior = GreetingBehavior.SETTLED,
    val target: GreetingTarget? = null,
    val remaining: Float = 0f,
    val score: Float = 0f,
    val interest: Map<GreetingTarget, Float> = emptyMap(),
    val gestureCooldown: Float = 0f,
    val gesture: GreetingGesture? = null,
    val gestureSerial: Int = 0,
)

/** The whole greeting: the cast and what the system and the user are doing. */
data class GreetingState(
    val actors: List<GreetingActor> = emptyList(),
    val connection: GreetingConnection = GreetingConnection.CONNECTED,
    val hydrating: Boolean = false,
    val reducedMotion: Boolean = false,
    val pointer: GreetingTarget? = null,
    val typing: Boolean = false,
    val booted: Boolean = false,
    val markBootSerial: Int = 0,
    val markSuccessSerial: Int = 0,
    val pending: List<GreetingCue> = emptyList(),
) {
    /** Something is playing or about to: the host keeps ticking while this holds, and rests otherwise. */
    val active: Boolean
        get() = !reducedMotion && (
            pending.isNotEmpty() ||
                actors.any { it.behavior != GreetingBehavior.SETTLED && it.behavior != GreetingBehavior.HUDDLED } ||
                actors.any { it.interest.isNotEmpty() || it.gestureCooldown > 0f } ||
                (actors.size > 1 && actors.any { it.mood.busy })
            )
}
