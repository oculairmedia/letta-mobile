package com.letta.mobile.desktop.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.letta.mobile.desktop.DesktopDestination

/*
 * How the desktop shell (LettaDesktopApp) folds into a phone. Each binding takes the window's
 * [DesktopPhoneChrome], which is null in the normal desktop app, and then answers exactly as the
 * desktop did before the phone preview existed.
 */

/** The phone's reduced-motion preset, or the OS's setting on the desktop. */
internal fun DesktopPhoneChrome?.reducedMotionOr(system: Boolean): Boolean = this?.state?.reducedMotion ?: system

/** The phone always draws the shared chat page (it is the phone's chat); the desktop follows its flag. */
internal fun DesktopPhoneChrome?.drawsSharedChat(flagEnabled: Boolean): Boolean = this != null || flagEnabled

/** On the phone the sidebar lives in the drawer, where it is always shown. */
internal fun DesktopPhoneChrome?.sidebarVisible(desktopVisible: Boolean): Boolean = this != null || desktopVisible

/** Where the agent pane's mascot stands: the open drawer on a phone, the visible sidebar on the desktop. */
internal fun DesktopPhoneChrome?.agentPaneVisible(sidebarVisible: Boolean): Boolean = this?.drawerOpen ?: sidebarVisible

/**
 * The chat page draws edge to edge with its own floating header; every other destination (and the
 * agent editor) gets the phone's app bar.
 */
internal fun isBareChatPage(destination: DesktopDestination, hasSharedChatPage: Boolean, editingAgentId: String?): Boolean =
    destination == DesktopDestination.Conversations && hasSharedChatPage && editingAgentId == null

/** [content] only on the desktop: what a phone's window says nothing about (its width, the side panes). */
@Composable
internal fun DesktopOnly(phone: DesktopPhoneChrome?, content: @Composable () -> Unit) {
    if (phone == null) content()
}

/** A phone's drawer closes once something in it was picked: a destination, a conversation, an agent to edit. */
@Composable
internal fun ClosePhoneDrawerOnNavigation(
    phone: DesktopPhoneChrome?,
    destination: DesktopDestination,
    conversationId: String?,
    editingAgentId: String?,
) {
    if (phone == null) return
    LaunchedEffect(destination, conversationId, editingAgentId) { phone.drawerOpen = false }
}

/** The desktop's navigation panes as the phone's drawer; nothing on the desktop, where they sit beside the content. */
@Composable
internal fun PhoneDrawerHost(phone: DesktopPhoneChrome?, reducedMotion: Boolean, panes: @Composable () -> Unit) {
    if (phone == null) return
    PhoneNavigationDrawer(open = phone.drawerOpen, onDismiss = { phone.drawerOpen = false }, reducedMotion = reducedMotion, content = panes)
}
