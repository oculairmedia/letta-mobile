package com.letta.mobile.ui.mascot

import com.letta.mobile.avatar.core.AvatarActivity
import com.letta.mobile.avatar.core.AvatarDirector
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence

/**
 * Feeds one presence change into the director. Activity, typing and approval are levels; an
 * error's rising edge is the error cue; a run that ends without an error is a completed task.
 * This is the whole mapping from what the app knows to what the character does - both platforms
 * call it, nothing else talks to the director about presence.
 *
 * letta-mobile-iczm7: an agent that is only dreaming dozes (the director's sleep pose); any turn,
 * typing, approval or error wakes it, since [AgentPresence.dozing] turns false.
 */
fun AvatarDirector.applyPresence(previous: AgentPresence, presence: AgentPresence) {
    if (presence == previous) return
    if (presence.dozing != previous.dozing) setQuietHours(presence.dozing)
    setActivity(
        when (presence.activity) {
            AgentActivityKind.THINKING -> AvatarActivity.THINKING
            AgentActivityKind.WORKING -> AvatarActivity.WORKING
            AgentActivityKind.DELEGATING -> AvatarActivity.DELEGATING
            AgentActivityKind.SPEAKING -> AvatarActivity.SPEAKING
            AgentActivityKind.IDLE -> AvatarActivity.IDLE
        },
    )
    setUserTyping(presence.userTyping)
    setAwaitingApproval(presence.awaitingApproval)
    if (presence.error && !previous.error) notifyError()
    if (previous.activity != AgentActivityKind.IDLE && presence.activity == AgentActivityKind.IDLE && !presence.error) {
        notifyTaskSucceeded()
    }
}
