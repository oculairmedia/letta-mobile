package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_open_chat
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.mascot.mascotAvailable
import com.letta.mobile.ui.theme.ChatMotionTokens
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import com.letta.mobile.ui.theme.TouchComposerDimens
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-bglj6.1.9: the agent's mascot above the Touch bar, at its left edge, only while the
 * agent works (legacy ChatComposerCompanion): the row opens as a run starts, the character rises
 * out of the bar, and both go when the run is done. The seat stays declared throughout (the page's
 * one companion seat stands over this spot), it is only shown or hidden.
 */
@Composable
internal fun TouchCompanionSlot(model: ComposerModel) {
    val agentId = model.uiState.agentId
    if (!LocalComposerCompanion.current || agentId == null || !mascotAvailable(agentId)) return
    val working = model.uiState.isStreaming || model.uiState.isAgentTyping
    val reducedMotion = LocalReducedMotion.current
    val shown by animateFloatAsState(
        targetValue = if (working) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(ChatMotionTokens.DockCollapse.MILLIS),
        label = "touchCompanionShown",
    )
    val anchors = LocalCompanionSeatAnchors.current
    SideEffect { anchors?.pageShown = shown }
    Box(
        Modifier
            .fillMaxWidth()
            .height(TouchComposerDimens.companion * shown)
            .padding(start = TouchComposerDimens.horizontalPadding),
        contentAlignment = Alignment.BottomStart,
    ) {
        // The seat keeps its full size and stands on the bar as the row opens around it.
        Box(Modifier.wrapContentHeight(Alignment.Bottom, unbounded = true)) {
            CompanionSeatHere(agentId, model, size = TouchComposerDimens.companion)
        }
    }
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
