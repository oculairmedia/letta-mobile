package com.letta.mobile.desktop.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.desktop.DesktopDestination

/*
 * How the desktop shell (LettaDesktopApp) folds into a phone. Each binding takes the window's
 * [DesktopPhoneChrome], which is null in the normal desktop app, and then answers exactly as the
 * desktop did before the phone preview existed. The rail and agent panel fold into the shared
 * workspace's drawer ([DesktopPhoneChrome.drawer]).
 */

/** The phone's reduced-motion preset, or the OS's setting on the desktop. */
internal fun DesktopPhoneChrome?.reducedMotionOr(system: Boolean): Boolean = this?.state?.reducedMotion ?: system

/**
 * The chat page draws edge to edge with its own floating header; every other destination (and the
 * agent editor) gets the phone's app bar.
 */
internal fun isBareChatPage(destination: DesktopDestination, agentEditor: ShellDestination.AgentEditor?): Boolean =
    destination == DesktopDestination.Conversations && agentEditor == null

/** A phone's drawer closes once something in it was picked: a destination, a conversation, an agent to edit. */
@Composable
internal fun ClosePhoneDrawerOnNavigation(
    phone: DesktopPhoneChrome?,
    destination: DesktopDestination,
    conversationId: String?,
    agentEditor: ShellDestination.AgentEditor?,
) {
    if (phone == null) return
    LaunchedEffect(destination, conversationId, agentEditor) { phone.drawerOpen = false }
}
