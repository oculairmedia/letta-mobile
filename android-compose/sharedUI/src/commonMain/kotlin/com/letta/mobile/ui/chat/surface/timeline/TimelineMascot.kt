package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.chat.AgentSphere
import com.letta.mobile.ui.mascot.MascotLoading
import com.letta.mobile.ui.mascot.MascotSeat
import com.letta.mobile.ui.mascot.MascotSeatVacancy
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.mascotAvailable
import com.letta.mobile.ui.theme.ChatMascotDimens
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags for the agent's mascot in the timeline. */
internal object TimelineMascotTags {
    const val LOADING = "chat-timeline-mascot-loading"
    const val WELCOME_HERO = "chat-timeline-welcome-hero"
}

/**
 * letta-mobile-bglj6.1: the first load of a conversation, shown as its agent: the mascot in its
 * loading orbit (desktop's canonical status row). Without a mascot for [agentId], the skeleton.
 */
@Composable
internal fun TimelineLoading(agentId: String?, modifier: Modifier = Modifier) {
    if (!mascotAvailable(agentId)) {
        TimelineLoadingSkeleton(modifier)
        return
    }
    Box(modifier.fillMaxSize().testTag(TimelineMascotTags.LOADING), contentAlignment = Alignment.Center) {
        MascotLoading(agentId)
    }
}

/** Older history paging in: the agent fetching it, else the plain spinner. */
@Composable
internal fun TimelineOlderHistoryLoading(agentId: String?) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        MascotLoading(agentId) { CircularProgressIndicator(Modifier.size(LettaDimens.Control.icon)) }
    }
}

/**
 * The fresh conversation's greeting: the agent itself at hero size, the mascot's rest seat while
 * the welcome shows (it moves down to the composer when the conversation starts). While the
 * character stands elsewhere (the agent pane is open) the seat stays bare; the sphere stands in
 * only when the agent has no mascot the host can draw. Nothing at all without a mascot, so a
 * host without a renderer keeps its plain greeting.
 */
@Composable
internal fun TimelineWelcomeHero(agentId: String?, onEditAgent: (() -> Unit)?) {
    if (agentId == null || !mascotAvailable(agentId)) return
    MascotSeat(
        agentId = agentId,
        stage = MascotStage.WELCOME_HERO,
        size = ChatMascotDimens.welcomeHero,
        modifier = Modifier.testTag(TimelineMascotTags.WELCOME_HERO),
        onEdit = onEditAgent,
    ) { vacancy ->
        if (vacancy == MascotSeatVacancy.NO_MASCOT) AgentSphere(size = ChatMascotDimens.welcomeFallbackSphere)
    }
}
