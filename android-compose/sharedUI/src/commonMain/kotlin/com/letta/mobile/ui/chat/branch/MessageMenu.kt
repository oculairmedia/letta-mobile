package com.letta.mobile.ui.chat.branch

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.letta.mobile.data.chat.projection.BranchActionSupport
import com.letta.mobile.data.chat.projection.MessageActionAvailability
import com.letta.mobile.data.chat.projection.messageActionAvailability
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_copy
import com.letta.mobile.sharedui.resources.rows_edit_and_resend
import com.letta.mobile.sharedui.resources.rows_fork_from_here
import com.letta.mobile.sharedui.resources.rows_rerun
import com.letta.mobile.sharedui.resources.rows_send_again
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.components.LettaMenuItem
import com.letta.mobile.ui.components.LettaPopupMenu
import com.letta.mobile.ui.icons.LettaIcons
import org.jetbrains.compose.resources.stringResource

/** letta-mobile-7mys7: the branch actions the owner supports, as the message-action policy reads them. */
fun ChatSurfaceCapabilities.branchSupport(): BranchActionSupport =
    BranchActionSupport(fork = fork, edit = editAndResend, rerun = rerunInFork)

/**
 * What a message's menu can ask its owner for. [ChatActions] answers it on the shared page
 * ([asMessageMenuActions]); desktop's own message cards bind it to the desktop controller.
 */
interface MessageMenuActions {
    fun forkFromMessage(message: UiMessage, name: String? = null)

    fun editAndResend(message: UiMessage)

    fun rerunInFork(message: UiMessage)

    /** "Send again": the owner's plain resend into this conversation. */
    fun sendAgain(message: UiMessage)
}

/** The shared page's owner as a [MessageMenuActions]. */
fun ChatActions.asMessageMenuActions(): MessageMenuActions {
    val owner = this
    return object : MessageMenuActions {
        override fun forkFromMessage(message: UiMessage, name: String?) = owner.forkFromMessage(message, name)

        override fun editAndResend(message: UiMessage) = owner.editAndResend(message)

        override fun rerunInFork(message: UiMessage) = owner.rerunInFork(message)

        override fun sendAgain(message: UiMessage) = owner.rerun(message)
    }
}

/**
 * letta-mobile-7mys7: one message's actions (Edit and resend, Rerun in a new branch, Send again,
 * Fork from here, Copy) and its fork-naming dialog, shared by every chat surface: the shared
 * timeline rows and desktop's own message cards. A surface opens it with [openMenu] (a long
 * press, a right-click) and can start a fork directly with [fork] (a hover button); it draws
 * [MessageMenuPopup] where the menu should anchor.
 */
@Stable
class MessageMenu internal constructor(
    val message: UiMessage,
    val availability: MessageActionAvailability,
    private val nameForks: Boolean,
    internal val actions: MessageMenuActions,
) {
    internal var open by mutableStateOf(false)
    internal var naming by mutableStateOf(false)

    /** Opens the menu; null when the message has no actions. */
    val openMenu: (() -> Unit)? = if (availability.hasActions) ({ open = true }) else null

    /** "Fork from here", asking for a name first when the owner can name forks; null when it cannot fork. */
    val fork: (() -> Unit)? = if (availability.canFork) ({ startFork() }) else null

    internal fun startFork() {
        if (nameForks) naming = true else actions.forkFromMessage(message)
    }
}

/**
 * The [MessageMenu] for [message]. [sendAgain] offers "Send again" (the owner's plain resend);
 * replies pass false, since only a prompt can be sent again or edited.
 */
@Composable
fun rememberMessageMenu(
    message: UiMessage,
    capabilities: ChatSurfaceCapabilities,
    actions: MessageMenuActions,
    sendAgain: Boolean = capabilities.rerun,
): MessageMenu = remember(message, capabilities, actions, sendAgain) {
    val availability = messageActionAvailability(message, message.content, sendAgain, capabilities.branchSupport())
    MessageMenu(message, availability, capabilities.nameForks, actions)
}

/** Draws [menu]'s popup while it is open, and its naming dialog while a fork is being named. */
@Composable
fun MessageMenuPopup(menu: MessageMenu) {
    if (menu.naming) {
        ForkNameDialog(
            onFork = { name ->
                menu.naming = false
                menu.actions.forkFromMessage(menu.message, name)
            },
            onDismiss = { menu.naming = false },
        )
    }
    if (!menu.open) return
    LettaPopupMenu(expanded = true, onDismiss = { menu.open = false }, items = menuItems(menu))
}

@Composable
private fun menuItems(menu: MessageMenu): List<LettaMenuItem> {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val message = menu.message
    val actions = menu.actions
    val available = menu.availability
    val edit = stringResource(Res.string.rows_edit_and_resend)
    val rerun = stringResource(Res.string.rows_rerun)
    val sendAgain = stringResource(Res.string.rows_send_again)
    val fork = stringResource(Res.string.rows_fork_from_here)
    val copy = stringResource(Res.string.rows_copy)
    return buildList {
        if (available.canEdit) add(LettaMenuItem(label = edit, icon = LettaIcons.Edit) { actions.editAndResend(message) })
        if (available.canRerun) add(LettaMenuItem(label = rerun, icon = LettaIcons.Refresh) { actions.rerunInFork(message) })
        if (available.canSendAgain) add(LettaMenuItem(label = sendAgain, icon = LettaIcons.Send) { actions.sendAgain(message) })
        if (available.canFork) add(LettaMenuItem(label = fork, icon = LettaIcons.ForkRight, onClick = menu::startFork))
        if (available.canCopy) add(LettaMenuItem(label = copy, icon = LettaIcons.Copy) { clipboard.setText(AnnotatedString(message.content)) })
    }
}
