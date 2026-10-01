package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.mascot.LocalMascotTransport
import com.letta.mobile.ui.mascot.MascotSeat
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.mascotAvailable
import com.letta.mobile.ui.theme.ChatMascotDimens
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags for the composer companion. */
internal object ComposerCompanionTags {
    /** The slot at the prompt's left edge; its width is the companion's share of the row. */
    const val SLOT = "chat-composer-companion-slot"
}

/**
 * False where the agent's mascot already stands somewhere else on the same surface (the
 * collapsed dock seats it above the bar): the composer then draws no companion slot, so the
 * [MascotStage.COMPOSER_COMPANION] seat is declared exactly once.
 */
internal val LocalComposerCompanion = staticCompositionLocalOf { true }

/**
 * letta-mobile-bglj6.1: the agent keeps the user company at the prompt. Its mascot hangs off the
 * left edge of [content] (the full card or the docked bar) in a [MascotStage.COMPOSER_COMPANION]
 * seat of the window's mascot transport, so opening the agent pane or the editor flies the
 * character over and closing it flies it back. The slot closes while the character stands
 * elsewhere, giving the prompt its full width back, and reopens as it returns.
 *
 * The prompt keeps its centred max width; the slot is added to the row's, so the pair is centred
 * together. Without a mascot for the conversation's agent the row is just [content].
 */
@Composable
internal fun ComposerCompanionRow(model: ComposerModel, content: @Composable () -> Unit) {
    val agentId = model.uiState.agentId
    // One structure whether or not the companion sits here: the minimised dock turns it off and
    // restoring turns it back on, and [content] (the prompt field) must stay the same instance.
    val available = LocalComposerCompanion.current && agentId != null && mascotAvailable(agentId)
    val present = available && LocalMascotTransport.current.activeStage(agentId!!) == MascotStage.COMPOSER_COMPANION
    val slot by animateDpAsState(
        targetValue = if (present) ChatMascotDimens.composerCompanionSlot else 0.dp,
        label = "composerCompanionSlot",
    )
    Row(
        // Order matters: widthIn BEFORE fillMaxWidth, or fillMaxWidth pins min == max and the cap is lost.
        modifier = Modifier.widthIn(max = ChatColumnMaxWidth + slot).fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        // Kept while it closes, so the bar widens smoothly when the companion leaves the row.
        if (available || slot > 0.dp) {
            // Composed even while the slot is closed: the seat must stay declared for the
            // character to come back to it.
            Box(
                Modifier.testTag(ComposerCompanionTags.SLOT).width(slot).padding(end = LettaDimens.Space.lg),
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (available) CompanionSeatHere(agentId!!, model)
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) { content() }
    }
}

/** On a chat page, a spot the page's one seat stands over; elsewhere, a seat of its own. */
@Composable
private fun CompanionSeatHere(agentId: String, model: ComposerModel) {
    val anchors = LocalCompanionSeatAnchors.current
    if (anchors != null) {
        CompanionSeatAnchor(anchors)
    } else {
        MascotSeat(
            agentId = agentId,
            stage = MascotStage.COMPOSER_COMPANION,
            size = ChatMascotDimens.composerCompanion,
            onClick = model.host.openAgentPane,
            onEdit = model.host.editAgent,
            empty = {},
        )
    }
}
