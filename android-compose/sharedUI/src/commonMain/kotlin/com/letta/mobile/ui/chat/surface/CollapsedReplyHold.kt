package com.letta.mobile.ui.chat.surface

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.delay

/*
 * letta-mobile-zxk2n.29: the minimised chat's reply bubble goes on its own. Once its text settles
 * (not while it streams, a tool runs or the turn waits on the person) it stays for the person's
 * hold (CanvasBubbleLayout.replyHoldMillis: by default longer for a longer reply, or "always"),
 * then fades out as a dismissal would. The pointer over it, or focus inside it, holds it; leaving
 * starts the hold again. The reply itself stays in the conversation.
 */

/** How the reply bubble comes and goes, and what it watches (hover and focus) to hold itself. */
@Immutable
internal class ReplyHold(val enter: EnterTransition, val exit: ExitTransition, val watch: Modifier)

/** Dismisses [turn]'s bubble ([dismiss]) once its hold runs out while it is [shown], settled and left alone. */
@Composable
internal fun rememberReplyHold(turn: CollapsedTurn, shown: Boolean, dismiss: () -> Unit): ReplyHold {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val millis = currentBubbleLayout().replyHoldMillis(turn.text.length)
    val settled = shown && turn.settledForHold()
    val currentDismiss by rememberUpdatedState(dismiss)
    LaunchedEffect(turn.dismissKey, turn.text, settled, hovered, focused, millis) {
        if (!settled || hovered || focused || millis == null) return@LaunchedEffect
        delay(millis)
        currentDismiss()
    }
    val reduced = LocalReducedMotion.current
    val watch = Modifier.hoverable(hover).onFocusChanged { focused = it.hasFocus }
    return remember(reduced) {
        if (reduced) {
            ReplyHold(EnterTransition.None, ExitTransition.None, watch)
        } else {
            ReplyHold(fadeIn(tween(LettaMotionTokens.ENTER_MILLIS)), fadeOut(tween(REPLY_FADE_OUT_MILLIS)), watch)
        }
    }
}

/** Only a finished reply goes on its own: not while it streams, a tool runs or the person is asked something. */
private fun CollapsedTurn.settledForHold(): Boolean = text.isNotBlank() && !streaming && !working && !needsInput

/** The bubble fades out gently: it was not asked to go. */
private const val REPLY_FADE_OUT_MILLIS = 400
