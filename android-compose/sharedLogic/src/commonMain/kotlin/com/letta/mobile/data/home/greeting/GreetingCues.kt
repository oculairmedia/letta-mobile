package com.letta.mobile.data.home.greeting

import kotlin.random.Random

/** The cues a moment raises, read against the state before the moment is recorded. */
internal fun cuesFor(state: GreetingState, input: GreetingInput): List<GreetingCue> = when (input) {
    is GreetingInput.Cast -> moodCues(state, input.members)
    is GreetingInput.RunFinished ->
        if (input.ok) listOf(cue(GreetingCueKind.NEIGHBOUR_DONE, GreetingTarget.Actor(input.id), DONE)) else emptyList()
    is GreetingInput.Arrived -> listOf(cue(GreetingCueKind.USER_HELLO, GreetingTarget.User, if (input.returning) RETURN_HELLO else HELLO))
    is GreetingInput.PointerOver -> pointerCues(state, input.target)
    is GreetingInput.Tapped -> tapCues(input.target)
    is GreetingInput.Typing ->
        if (input.active && !state.typing) listOf(cue(GreetingCueKind.USER_TYPING, GreetingTarget.User, TYPING)) else emptyList()
    is GreetingInput.Connection ->
        if (input.connection != state.connection) listOf(cue(GreetingCueKind.MARK_CHANGED, GreetingTarget.Mark, MARK)) else emptyList()
    else -> emptyList()
}

/** A busy mascot's moment for its neighbours: the strength it starts at when it only catches an eye. */
internal fun busyAmbientCue(id: String): GreetingCue = cue(GreetingCueKind.NEIGHBOUR_BUSY, GreetingTarget.Actor(id), BUSY_AMBIENT)

/**
 * A quiet moment's glance: one idle mascot (not busy, not already reacting) turns to a neighbour beside
 * it, both picked by [random]. Null when no one is free or the mascot is alone on the stage.
 */
internal fun idleGlance(state: GreetingState, random: Random): GreetingCue? {
    val actors = state.actors
    val free = actors.indices.filter { actors[it].behavior == GreetingBehavior.SETTLED && !actors[it].mood.busy }
    if (actors.size < 2 || free.isEmpty()) return null
    val index = free[random.nextInt(free.size)]
    val neighbour = when (index) {
        0 -> 1
        actors.lastIndex -> index - 1
        else -> if (random.nextBoolean()) index - 1 else index + 1
    }
    return GreetingCue(GreetingCueKind.IDLE_GLANCE, GreetingTarget.Actor(actors[neighbour].id), IDLE_GLANCE, only = actors[index].id)
}

private fun cue(kind: GreetingCueKind, source: GreetingTarget, salience: Float) = GreetingCue(kind, source, salience)

private fun moodCues(state: GreetingState, members: List<GreetingCastMember>): List<GreetingCue> = members.mapNotNull { member ->
    val before = state.actors.firstOrNull { it.id == member.id }?.mood ?: return@mapNotNull null
    val source = GreetingTarget.Actor(member.id)
    when {
        member.mood.troubled && !before.troubled -> cue(GreetingCueKind.NEIGHBOUR_TROUBLE, source, TROUBLE)
        member.mood.busy && !before.busy -> cue(GreetingCueKind.NEIGHBOUR_BUSY, source, BUSY_START)
        else -> null
    }
}

private fun pointerCues(state: GreetingState, target: GreetingTarget?): List<GreetingCue> {
    if (target == null || target == state.pointer || target == GreetingTarget.User) return emptyList()
    return listOf(cue(GreetingCueKind.POINTED, target, POINTED))
}

private fun tapCues(target: GreetingTarget): List<GreetingCue> = when (target) {
    // The tapped mascot waves back at the user; the others look at it.
    is GreetingTarget.Actor -> listOf(
        GreetingCue(GreetingCueKind.USER_HELLO, GreetingTarget.User, HELLO, only = target.id),
        cue(GreetingCueKind.TAPPED, target, TAPPED),
    )
    GreetingTarget.Mark -> listOf(cue(GreetingCueKind.MARK_CHANGED, GreetingTarget.Mark, TAPPED))
    GreetingTarget.User -> emptyList()
}

// How strongly each moment starts out.
private const val HELLO = 1f
private const val RETURN_HELLO = 0.8f
private const val DONE = 0.9f
private const val TROUBLE = 0.85f
private const val BUSY_START = 0.5f
private const val BUSY_AMBIENT = 0.35f
private const val POINTED = 0.6f
private const val TAPPED = 0.75f
private const val TYPING = 0.5f
private const val MARK = 0.7f
private const val IDLE_GLANCE = 0.45f
