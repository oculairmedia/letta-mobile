package com.letta.mobile.ui.mascot

import com.letta.mobile.avatar.core.AvatarActivity
import com.letta.mobile.avatar.core.MascotDirector
import com.letta.mobile.avatar.core.MascotEvent
import com.letta.mobile.avatar.core.MascotSignals
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.data.presence.RunEdges

/**
 * The whole mapping from what the app knows about an agent to what its mascot does (letta-mobile-
 *): an [AgentPresence] becomes the director's [MascotSignals] (levels), and the growth of
 * its run edge counts since the last presence becomes [MascotEvent]s (moments). Both platforms
 * reach the director through this and nothing else.
 *
 * The first presence only sets the baseline: an entry created mid-run must not replay the sends
 * and finishes that happened before it existed.
 */
internal class MascotPresenceSignals {
    private var last: AgentPresence? = null
    private var platform = MascotPlatformFacts()

    fun apply(director: MascotDirector, presence: AgentPresence) {
        val previous = last
        last = presence
        director.setSignals(presence.toMascotSignals(platform))
        if (previous != null) mascotEvents(previous, presence).forEach(director::onEvent)
    }

    fun setPlatform(director: MascotDirector, facts: MascotPlatformFacts) {
        if (facts == platform) return
        platform = facts
        director.setSignals((last ?: AgentPresence.IDLE).toMascotSignals(facts))
    }
}

/** The platform's part of the signals: what the host knows that presence does not. */
data class MascotPlatformFacts(val reduceMotion: Boolean = false, val pointerPresent: Boolean = false)

internal fun AgentPresence.toMascotSignals(platform: MascotPlatformFacts): MascotSignals = MascotSignals(
    activity = activity.toAvatarActivity(),
    awaitingApproval = awaitingApproval,
    userTyping = userTyping,
    error = error,
    dreaming = dreaming,
    toolName = toolName,
    reduceMotion = platform.reduceMotion,
    pointerPresent = platform.pointerPresent,
)

/**
 * The moments between two presences, in the order a turn has them. A count that went down (a
 * conversation was cleared) is a new baseline, not a moment.
 */
internal fun mascotEvents(previous: AgentPresence, next: AgentPresence): List<MascotEvent> {
    val was = previous.edges
    val now = next.edges
    if (was == now) return emptyList()
    return buildList {
        if (now.sent > was.sent) add(MascotEvent.Sent)
        if (now.toolsStarted > was.toolsStarted) add(MascotEvent.ToolStarted(next.toolName))
        if (now.toolsEnded > was.toolsEnded) add(MascotEvent.ToolEnded)
        if (now.approvals > was.approvals) add(MascotEvent.ApprovalRaised)
        addFinishes(was, now)
    }
}

private fun MutableList<MascotEvent>.addFinishes(was: RunEdges, now: RunEdges) {
    if (now.completed > was.completed) add(MascotEvent.RunFinished(ok = true))
    if (now.cancelled > was.cancelled) add(MascotEvent.RunFinished(ok = false))
    if (now.failed > was.failed) add(MascotEvent.RunFailed)
}

private fun AgentActivityKind.toAvatarActivity(): AvatarActivity = when (this) {
    AgentActivityKind.THINKING -> AvatarActivity.THINKING
    AgentActivityKind.WORKING -> AvatarActivity.WORKING
    AgentActivityKind.DELEGATING -> AvatarActivity.DELEGATING
    AgentActivityKind.SPEAKING -> AvatarActivity.SPEAKING
    AgentActivityKind.IDLE -> AvatarActivity.IDLE
}
