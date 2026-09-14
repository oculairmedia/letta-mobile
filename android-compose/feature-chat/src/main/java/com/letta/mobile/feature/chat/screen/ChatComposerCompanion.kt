package com.letta.mobile.feature.chat.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.ui.components.rememberReducedMotionEnabled
import com.letta.mobile.ui.mascot.LocalMascotRegistry
import com.letta.mobile.ui.mascot.MascotLive
import com.letta.mobile.ui.mascot.mascotAvailable

/**
 * The row above the composer while the agent is at work: the live mascot, thinking or speaking,
 * with [status] (the thinking indicator) beside it - one indicator, not one above the other. The
 * character rises out of the composer and grows from its own base, and is gone the moment the run
 * is done; it does not sit above the box at rest. The same process-wide entry every other tile of
 * this agent draws, so it arrives mid-thought rather than restarting. Without an identity or a
 * renderer only [status] draws, exactly where it did before.
 */
@Composable
internal fun ChatComposerCompanion(agentId: String?, status: (@Composable () -> Unit)?) {
    val registry = LocalMascotRegistry.current
    val identity = agentId?.let { registry.identities[it] }
    val hasMascot = agentId != null && identity != null && mascotAvailable(agentId)
    if (!hasMascot && status == null) return
    val atWork = agentId != null &&
        registry.presence[agentId]?.activity.let { it != null && it != AgentActivityKind.IDLE }
    val reducedMotion = rememberReducedMotionEnabled()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ChatComposerInputHorizontalPadding, bottom = ChatComposerCompanionGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ChatComposerCompanionGap),
    ) {
        if (hasMascot) {
            // The motion belongs to the character, not the row: visibility wraps the mascot alone.
            val base = TransformOrigin(pivotFractionX = 0.5f, pivotFractionY = 1f)
            AnimatedVisibility(
                visible = atWork,
                enter = if (reducedMotion) {
                    fadeIn()
                } else {
                    fadeIn() + slideInVertically { it / 2 } + scaleIn(initialScale = 0.5f, transformOrigin = base)
                },
                exit = if (reducedMotion) {
                    fadeOut()
                } else {
                    fadeOut() + slideOutVertically { it / 2 } + scaleOut(targetScale = 0.5f, transformOrigin = base)
                },
            ) {
                MascotLive(checkNotNull(agentId), checkNotNull(identity), size = ChatComposerCompanionSize)
            }
        }
        // The rig draws the body below its surface's centre (see the 8yee3 framing follow-up), so the
        // status drops by that much to sit on the eye line rather than on the surface's midline.
        status?.let { Box(Modifier.padding(top = ChatComposerCompanionBodyDrop)) { it() } }
    }
}

/** The companion's surface; the body spans ~60 % of it, so this reads as a ~34 dp character. */
private val ChatComposerCompanionSize = 56.dp
private val ChatComposerCompanionGap = 2.dp
private val ChatComposerCompanionBodyDrop = 7.dp
