package com.letta.mobile.desktop

import androidx.compose.ui.input.key.Key
import com.letta.mobile.data.chat.runtime.ConversationListGroups
import com.letta.mobile.data.chat.runtime.ConversationListOrder
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.ui.shell.shortcuts.ShortcutFocusOwner
import com.letta.mobile.ui.shell.shortcuts.ShortcutHub
import com.letta.mobile.ui.shell.shortcuts.ShortcutKeystroke
import com.letta.mobile.ui.shell.shortcuts.ShortcutModifiers
import java.awt.Component
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.text.JTextComponent

/**
 * Sidebar list order (pinned first, settled last) so prev/next conversation matches the panel.
 * Binding only — the keymap itself lives in sharedUI (letta-mobile-k5nbq.4).
 */
internal fun desktopConversationOrder(
    conversations: List<DesktopConversationSummary>,
    groups: ConversationListGroups,
): List<String> = ConversationListOrder.arrange(conversations, groups) { it.id }.map { it.id }

/**
 * letta-mobile-k5nbq.11: a key typed while a Swing view embedded through SwingPanel (a JCEF plugin
 * page among them) holds AWT focus never enters Compose's key pipeline, so the window's AWT
 * dispatcher hands it to the shared [hub]. Keys on the Compose layer are left to the shell's own
 * key modifier. Returns the uninstaller.
 */
internal fun installSwingShortcuts(hub: ShortcutHub): () -> Unit {
    val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val dispatcher = KeyEventDispatcher { event -> dispatchSwingShortcut(event, hub) }
    manager.addKeyEventDispatcher(dispatcher)
    return { manager.removeKeyEventDispatcher(dispatcher) }
}

internal fun dispatchSwingShortcut(event: KeyEvent, hub: ShortcutHub): Boolean {
    if (event.id != KeyEvent.KEY_PRESSED) return false
    val owner = swingFocusOwner(event.component) ?: return false
    return hub.dispatch(event.asStroke(), owner)
}

/** Null on the Compose layer; otherwise the kind of embedded Swing view [component] is. */
internal fun swingFocusOwner(component: Component?): ShortcutFocusOwner? {
    if (component == null || !hasSwingInteropAncestor(component)) return null
    return if (component is JTextComponent) ShortcutFocusOwner.ForeignTextField else ShortcutFocusOwner.ForeignView
}

private fun hasSwingInteropAncestor(component: Component): Boolean =
    generateSequence(component) { it.parent }.any { node ->
        node.javaClass.name == SWING_INTEROP_GROUP_CLASS ||
            (node as? JComponent)?.getClientProperty(FOREIGN_SWING_VIEW_PROP) == true
    }

internal const val FOREIGN_SWING_VIEW_PROP = "letta.foreignSwingView"
private const val SWING_INTEROP_GROUP_CLASS = "androidx.compose.ui.awt.SwingInteropViewGroup"

internal fun KeyEvent.asStroke(): ShortcutKeystroke = ShortcutKeystroke(
    key = Key(keyCode, keyLocation),
    down = id == KeyEvent.KEY_PRESSED,
    modifiers = ShortcutModifiers(ctrlOrMeta = isControlDown || isMetaDown, shift = isShiftDown, alt = isAltDown),
)
