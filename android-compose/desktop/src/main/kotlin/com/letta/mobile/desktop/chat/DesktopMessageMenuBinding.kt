package com.letta.mobile.desktop.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.branch.MessageMenu
import com.letta.mobile.ui.chat.branch.MessageMenuActions
import com.letta.mobile.ui.chat.branch.MessageMenuPopup
import com.letta.mobile.ui.chat.branch.onSecondaryClick
import com.letta.mobile.ui.chat.branch.rememberMessageMenu
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/**
 * letta-mobile-7mys7: the shared message menu (Edit and resend, Rerun in a new branch, Fork from
 * here, Copy) on desktop's own message cards, the page desktop shows by default. Null when the page
 * has no controller to branch through; the cards then keep Copy only.
 */
internal data class DesktopMessageMenuBinding(
    val capabilities: ChatSurfaceCapabilities,
    val actions: MessageMenuActions,
)

internal val LocalDesktopMessageMenu = staticCompositionLocalOf<DesktopMessageMenuBinding?> { null }

/** The menu's capabilities and actions for the selected conversation, read from [controller] now. */
internal fun desktopMessageMenu(controller: DesktopChatController): DesktopMessageMenuBinding {
    val management = controller.conversationManagement
    val branching = management.supportsBranching
    return DesktopMessageMenuBinding(
        capabilities = ChatSurfaceCapabilities(
            rerun = false,
            fork = branching,
            editAndResend = branching,
            rerunInFork = branching,
            nameForks = branching && management.canNameForks,
        ),
        actions = DesktopMessageMenuActions(controller),
    )
}

/**
 * Forwards the menu to the controller's conversation management, planning against the selected
 * conversation's messages. Equal per controller, so an open menu survives a recomposition.
 */
private data class DesktopMessageMenuActions(private val controller: DesktopChatController) : MessageMenuActions {
    override fun forkFromMessage(message: UiMessage, name: String?) =
        controller.conversationManagement.forkFrom(message, view(), name)

    override fun editAndResend(message: UiMessage) = controller.conversationManagement.editAndResend(message, view())

    override fun rerunInFork(message: UiMessage) = controller.conversationManagement.rerun(message, view())

    override fun sendAgain(message: UiMessage) = controller.sendText(message.content)

    /** The fork point is placed on the server's stored list; the page's messages only help find the row. */
    private fun view(): DesktopBranchView {
        val state = controller.state.value
        val messages = state.selectedConversationId?.let { state.messagesByConversationId[it] }.orEmpty()
        return DesktopBranchView(messages, hasOlderMessages = true)
    }
}

/** The shared [MessageMenu] for [message] on desktop's own cards; null when no binding is provided. */
@Composable
internal fun rememberDesktopMessageMenu(message: UiMessage, sendAgain: Boolean = false): MessageMenu? {
    val binding = LocalDesktopMessageMenu.current ?: return null
    return rememberMessageMenu(message, binding.capabilities, binding.actions, sendAgain)
}

/** A reply whose right-click opens its actions (Fork from here, Copy) where the backend can fork. */
@Composable
internal fun DesktopReplyMenuArea(message: UiMessage, content: @Composable () -> Unit) {
    val menu = rememberDesktopMessageMenu(message)
    Box(Modifier.onSecondaryClick(menu?.openMenu)) {
        content()
        menu?.let { MessageMenuPopup(it) }
    }
}

/**
 * The card's "More actions" button, shown on hover beside Copy, with the menu anchored to it. Hidden,
 * it keeps its place (no layout jump) and takes no clicks. Nothing when [menu] has no actions.
 */
@Composable
internal fun DesktopMessageMenuButton(menu: MessageMenu?, visible: Boolean) {
    val open = menu?.openMenu ?: return
    Box {
        IconButton(
            onClick = open,
            enabled = visible,
            modifier = Modifier.size(LettaDimens.Control.actionButton).alpha(if (visible) 1f else 0f),
        ) {
            Icon(
                LettaIcons.MoreVert,
                contentDescription = "More actions",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(LettaDimens.Control.icon),
            )
        }
        MessageMenuPopup(menu)
    }
}
