package com.letta.mobile.ui.mascot

import com.letta.mobile.data.home.greeting.GreetingMood
import com.letta.mobile.data.home.greeting.GreetingRunTally
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence

/*
 * What the Home greeting reads from an agent's presence, folded here beside the director's own
 * mapping (MascotPresenceSignals) so the greeting itself never touches presence types.
 */

/** [agent]'s presence as a greeting mood; no presence reads as idle. */
internal fun MascotIdentityRegistry.greetingMood(agent: AgentId): GreetingMood = presence[agent.value].greetingMood()

/** How many of [agent]'s runs have ended, well and not, as its presence counts them. */
internal fun MascotIdentityRegistry.greetingRunTally(agent: AgentId): GreetingRunTally {
    val edges = presence[agent.value]?.edges ?: return GreetingRunTally()
    return GreetingRunTally(ok = edges.completed, notOk = edges.failed + edges.cancelled)
}

internal fun AgentPresence?.greetingMood(): GreetingMood = when {
    this == null -> GreetingMood.IDLE
    error -> GreetingMood.ERROR
    awaitingApproval -> GreetingMood.WAITING
    activity == AgentActivityKind.THINKING -> GreetingMood.THINKING
    activity != AgentActivityKind.IDLE -> GreetingMood.WORKING
    dreaming -> GreetingMood.ASLEEP
    else -> GreetingMood.IDLE
}
