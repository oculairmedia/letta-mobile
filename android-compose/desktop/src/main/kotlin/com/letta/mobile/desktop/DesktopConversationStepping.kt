package com.letta.mobile.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.letta.mobile.data.chat.runtime.ConversationListGroups
import com.letta.mobile.data.chat.runtime.ConversationListOrder
import com.letta.mobile.data.chat.runtime.ConversationNavigation
import com.letta.mobile.data.chat.runtime.ConversationStep
import com.letta.mobile.data.model.ConversationId
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/**
 * letta-mobile-zxk2n.5 (G37): previous / next conversation from the keyboard: mod+Alt+Up and
 * mod+Alt+Down step through the sidebar's list as it is drawn (pinned first, settled last, under
 * the current filter). A global AWT dispatcher, like the command palette's, so it works whichever
 * field has focus.
 */
@Composable
internal fun ConversationStepKeyDispatcherEffect(context: DesktopShellContext, frame: DesktopShellFrame) {
    val management = context.core.chatController.conversationManagement
    val pinned by management.pinnedConversationIds.collectAsState()
    val settled by management.marks.settled.collectAsState()
    val orderedIds by rememberUpdatedState(
        ConversationListOrder.arrange(frame.lists.agentConversations, ConversationListGroups(pinned, settled)) { it.id }.map { it.id },
    )
    val currentId by rememberUpdatedState(frame.chatState.selectedConversationId)
    DisposableEffect(context) {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { event ->
            val step = conversationStepOf(event) ?: return@KeyEventDispatcher false
            ConversationNavigation.adjacent(orderedIds, currentId, step)?.let { target ->
                context.router.openConversation(ConversationId(target))
            }
            true
        }
        focusManager.addKeyEventDispatcher(dispatcher)
        onDispose { focusManager.removeKeyEventDispatcher(dispatcher) }
    }
}

/** The step a key press asks for: mod+Alt+Up is the previous conversation, mod+Alt+Down the next. */
internal fun conversationStepOf(event: KeyEvent): ConversationStep? {
    val chord = event.id == KeyEvent.KEY_PRESSED && event.isAltDown && (event.isControlDown || event.isMetaDown)
    if (!chord) return null
    return when (event.keyCode) {
        KeyEvent.VK_UP -> ConversationStep.Previous
        KeyEvent.VK_DOWN -> ConversationStep.Next
        else -> null
    }
}
