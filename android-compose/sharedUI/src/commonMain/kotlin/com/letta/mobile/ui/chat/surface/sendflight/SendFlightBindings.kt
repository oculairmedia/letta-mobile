package com.letta.mobile.ui.chat.surface.sendflight

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.math.roundToInt

/**
 * letta-mobile-cc25e: marks the prompt field as where a sent prompt takes off. A no-op outside
 * a chat page.
 */
@Composable
internal fun rememberSendFlightSource(): Modifier {
    val state = LocalSendFlight.current ?: return Modifier
    return remember(state) { Modifier.onGloballyPositioned { state.reportSource(it.unclippedBoundsInRoot()) } }
}

/**
 * letta-mobile-cc25e: marks the user-prompt row [rowId] (its identity across the optimistic ->
 * server swap: the otid where it has one) showing [text] as a landing spot. The row that
 * claims a flight opens its slot with an eased insert and stays hidden until the ghost lands
 * on it; every other row is untouched. A no-op outside a chat page.
 */
@Composable
internal fun rememberSendFlightTarget(rowId: String, text: String): Modifier {
    val state = LocalSendFlight.current ?: return Modifier
    // Keyed on the row's identity: a slot reused for a newer message is a new row.
    val key = remember(state, rowId) { SendFlightRowKey() }
    // Captured at first composition: a row already on screen before the send never claims it.
    val bornAt = remember(state, rowId) { state.generation }
    val row = remember(key, bornAt, text) { SendFlightRow(key, bornAt, text) }
    return remember(state, row) {
        Modifier
            .graphicsLayer { alpha = state.rowAlpha(row) }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val height = (placeable.height * state.rowInsert(row)).roundToInt()
                layout(placeable.width, height) { placeable.place(0, 0) }
            }
            .onGloballyPositioned { state.reportTarget(row, it.unclippedBoundsInRoot()) }
    }
}

/**
 * letta-mobile-cc25e: [ChatActions] whose send launches a flight for [draft] first. The send
 * itself goes straight through; under reduced motion nothing flies.
 */
@Composable
internal fun rememberSendFlightActions(actions: ChatActions, draft: String): ChatActions {
    val state = LocalSendFlight.current ?: return actions
    val reducedMotion = LocalReducedMotion.current
    if (reducedMotion) return actions
    val currentDraft = rememberUpdatedState(draft)
    return remember(actions, state) { SendFlightActions(actions, state, currentDraft) }
}

private class SendFlightActions(
    private val delegate: ChatActions,
    private val state: SendFlightState,
    private val draft: State<String>,
) : ChatActions by delegate {
    override fun send() {
        state.launch(draft.value)
        delegate.send()
    }
}

/** Unclipped: a row still opening its slot hangs below the list's edge, and so does its target. */
private fun LayoutCoordinates.unclippedBoundsInRoot(): Rect = Rect(positionInRoot(), size.toSize())
