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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.mascot.LocalMascotTransport
import com.letta.mobile.ui.mascot.MascotSeat
import com.letta.mobile.ui.mascot.MascotStage
import com.letta.mobile.ui.mascot.mascotAvailable
import com.letta.mobile.ui.theme.ChatMascotDimens
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.math.roundToInt

/** Test tags for the composer companion. */
internal object ComposerCompanionTags {
    /** The slot at the prompt's left edge; its width is the companion's share of the row. */
    const val SLOT = "chat-composer-companion-slot"
}

/**
 * False where the agent's mascot already stands somewhere else on the same surface (the open
 * docked panel seats it in its top-centre badge, the collapsed dock above the bar): the composer
 * then draws no companion slot and keeps its full width, and the
 * [MascotStage.COMPOSER_COMPANION] seat is declared exactly once.
 */
internal val LocalComposerCompanion = staticCompositionLocalOf { true }

/**
 * The agent keeps the user company at the prompt. Its mascot hangs off the
 * left edge of [content] (the full card or the docked bar) in a [MascotStage.COMPOSER_COMPANION]
 * seat of the window's mascot transport, so opening the agent pane or the editor flies the
 * character over and closing it flies it back. The slot closes while the character stands
 * elsewhere, giving the prompt its full width back, and reopens as it returns.
 *
 * The pair keeps the chat column's max width: the mascot takes its slot at the column's start and the
 * prompt narrows by the slot, so the prompt's end lines up with the column (the transcript, the
 * chips and the hints above and below it) whether or not the mascot is there. Without a mascot for
 * the conversation's agent the row is just [content].
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
        // The slot comes out of the column's width, not on top of it: a wider row would push the
        // prompt's end past the column.
        modifier = Modifier.widthIn(max = composerBarMaxWidth()).fillMaxWidth(),
        // The companion's anchor occupies the seat box, so bottom-aligning it leaves the visible
        // character above the prompt's midpoint as the card grows taller than the seat.
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Kept while it closes, so the bar widens smoothly when the companion leaves the row.
        if (available || slot > 0.dp) {
            // Composed even while the slot is closed: the seat must stay declared for the
            // character to come back to it.
            Box(
                Modifier
                    .testTag(ComposerCompanionTags.SLOT)
                    .width(slot)
                    .heightFollowsSlot { slot / ChatMascotDimens.composerCompanionSlot }
                    .padding(end = LettaDimens.Space.lg),
                contentAlignment = Alignment.Center,
            ) {
                if (available) CompanionSeatHere(agentId!!, model)
            }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * The seat's share of the row's height follows the slot's open [fraction]: a closed slot (the
 * character stands elsewhere) adds no height, so the row is the prompt's own height and nothing
 * above or below the prompt moves away from it; an open slot keeps the full seat. The seat stays
 * composed and centred on the row either way, so the character can come back to it.
 */
private fun Modifier.heightFollowsSlot(fraction: () -> Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val height = (placeable.height * fraction().coerceIn(0f, 1f)).roundToInt()
    layout(placeable.width, height) { placeable.place(0, (height - placeable.height) / 2) }
}

/** On a chat page, a spot the page's one seat stands over; elsewhere, a seat of its own. */
@Composable
internal fun CompanionSeatHere(agentId: String, model: ComposerModel, size: Dp = ChatMascotDimens.composerCompanion) {
    val anchors = LocalCompanionSeatAnchors.current
    if (anchors != null) {
        CompanionSeatAnchor(anchors, size = size)
    } else {
        MascotSeat(
            agentId = agentId,
            stage = MascotStage.COMPOSER_COMPANION,
            size = size,
            onClick = model.host.openAgentPane,
            onEdit = model.host.editAgent,
            empty = {},
        )
    }
}
