package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_open_chat
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.surface.timeline.ThinkingStatusText
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.mascot.mascotAvailable
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.TouchComposerDimens
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1.9 / .21: the agent's mascot above the Touch bar, at its left edge, only
 * while the agent works (legacy ChatComposerCompanion). The row opens as a run starts, the
 * character rises out of the bar with elapsed time and the running tool beside it, and both go
 * when the run is done. The seat stays declared throughout (the page's one companion seat stands
 * over this spot); it is only shown or hidden.
 */
@Composable
internal fun TouchCompanionSlot(model: ComposerModel) {
    val agentId = model.uiState.agentId
    if (!LocalComposerCompanion.current || agentId == null) return
    if (!mascotAvailable(agentId)) return
    val presence = LocalMascotRegistry.current.presence[agentId]
    val working = companionWorking(model.uiState, presence)
    val reducedMotion = LocalReducedMotion.current
    val shown by animateFloatAsState(
        targetValue = if (working) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(ChatMotionTokens.DockCollapse.MILLIS),
        label = "touchCompanionShown",
    )
    val anchors = LocalCompanionSeatAnchors.current
    SideEffect { anchors?.pageShown = shown }
    Row(
        Modifier
            .fillMaxWidth()
            .height(TouchComposerDimens.companion * shown)
            .padding(start = TouchComposerDimens.horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        // The seat keeps its full size and stands on the bar as the row opens around it.
        Box(Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true)) {
            CompanionSeatHere(agentId, model, size = TouchComposerDimens.companion)
        }
        if (shown > 0f) {
            // The rig draws the body below its surface's centre, so the status drops onto the eye line.
            Box(
                Modifier
                    .weight(1f)
                    .padding(top = LettaDimens.Space.sm, end = LettaDimens.Space.lg)
                    .testTag(ComposerTestTags.TOUCH_COMPANION_STATUS),
            ) {
                ThinkingStatusText(
                    messages = model.uiState.messages,
                    delayMessage = model.uiState.a2uiThinkingDelayMessage,
                )
            }
        }
    }
}

/**
 * The companion is up while a turn is live: streaming, typing, an A2UI delay line, or any
 * presence activity other than idle (legacy ChatComposerCompanion used presence; the shared
 * page previously missed a working tool that had not yet flipped isStreaming).
 */
internal fun companionWorking(uiState: ChatUiState, presence: AgentPresence?): Boolean {
    if (uiState.isStreaming || uiState.isAgentTyping) return true
    if (!uiState.a2uiThinkingDelayMessage.isNullOrBlank()) return true
    val activity = presence?.activity
    return activity != null && activity != AgentActivityKind.IDLE
}

/** The canvas bar's way up to the chat: a chevron at its start (swiping the bar up does the same). */
@Composable
internal fun TouchOpenChatButton(onOpen: () -> Unit) {
    Box(
        Modifier
            .size(TouchComposerDimens.actionTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onOpen)
            .testTag(ComposerTestTags.TOUCH_RESTORE),
        contentAlignment = Alignment.Center,
    ) {
        DisclosureChevron(
            expanded = false,
            modifier = Modifier.size(LettaDimens.Space.xl),
            contentDescription = stringResource(Res.string.composer_open_chat),
            opensUpward = true,
        )
    }
}
