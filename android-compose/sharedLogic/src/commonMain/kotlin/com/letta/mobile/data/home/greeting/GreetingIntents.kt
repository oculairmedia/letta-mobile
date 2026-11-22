package com.letta.mobile.data.home.greeting

/**
 * What one mascot should do right now, in terms its rig already plays: where to [look], whether to
 * [perk] up (hover), which way to [lean] or [hop], and a one-shot [gesture] to play each time
 * [gestureSerial] goes up. Its sustained state (thinking, working, error) is never here: that stays
 * the agent's presence.
 */
data class GreetingIntent(
    val look: GreetingTarget? = null,
    val perk: Boolean = false,
    val lean: GreetingTarget? = null,
    val hop: GreetingTarget? = null,
    val gesture: GreetingGesture? = null,
    val gestureSerial: Int = 0,
)

/** The mark's states: app and connection only, never a turn. */
enum class GreetingMarkState {
    IDLE,
    LOADING,
    CONNECTING,
    DEGRADED,
}

/**
 * What the Baken mark should show: its [state], whether the pointer is on it, and its one-shots
 * (`boot` each time [bootSerial] goes up, `success` each time [successSerial] does).
 */
data class GreetingMarkIntent(
    val state: GreetingMarkState = GreetingMarkState.IDLE,
    val hovered: Boolean = false,
    val bootSerial: Int = 0,
    val successSerial: Int = 0,
    val reduceMotion: Boolean = false,
)

/** [id]'s intent. Under reduced motion every mascot only looks at the user. */
fun GreetingState.intentFor(id: String): GreetingIntent {
    val actor = actors.firstOrNull { it.id == id } ?: return GreetingIntent()
    if (reducedMotion) return GreetingIntent(look = GreetingTarget.User, gestureSerial = actor.gestureSerial)
    val target = actor.target
    val perk = pointer == GreetingTarget.Actor(id)
    // A hovered mascot that has nothing else to do looks up at the person hovering it.
    val base = GreetingIntent(look = target ?: GreetingTarget.User.takeIf { perk }, perk = perk, gestureSerial = actor.gestureSerial)
    return when (actor.behavior) {
        GreetingBehavior.SETTLED, GreetingBehavior.NOTICING, GreetingBehavior.CURIOUS -> base
        GreetingBehavior.GREETING -> base.copy(gesture = actor.gesture)
        GreetingBehavior.CHEERING -> base.copy(hop = target, gesture = actor.gesture)
        GreetingBehavior.CONCERNED, GreetingBehavior.HUDDLED -> base.copy(lean = target)
    }
}

/**
 * The mark's intent. Hydration shows only while no mascot is on screen (with mascots there, they
 * carry the life of the page and the mark stays calm).
 */
val GreetingState.markIntent: GreetingMarkIntent
    get() = GreetingMarkIntent(
        state = when {
            connection == GreetingConnection.CONNECTING -> GreetingMarkState.CONNECTING
            connection == GreetingConnection.DEGRADED -> GreetingMarkState.DEGRADED
            hydrating && actors.isEmpty() -> GreetingMarkState.LOADING
            else -> GreetingMarkState.IDLE
        },
        hovered = !reducedMotion && pointer == GreetingTarget.Mark,
        bootSerial = markBootSerial,
        successSerial = markSuccessSerial,
        reduceMotion = reducedMotion,
    )

/** The greeting line by the hour of day (0..23), short and warm. */
fun greetingLine(hour: Int): String = when (hour) {
    in MORNING_START until AFTERNOON_START -> "Good morning"
    in AFTERNOON_START until EVENING_START -> "Good afternoon"
    in EVENING_START until NIGHT_START -> "Good evening"
    else -> "Still up?"
}

private const val MORNING_START = 5
private const val AFTERNOON_START = 12
private const val EVENING_START = 17
private const val NIGHT_START = 22
