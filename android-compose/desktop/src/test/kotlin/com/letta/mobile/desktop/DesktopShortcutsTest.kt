package com.letta.mobile.desktop

import com.letta.mobile.desktop.plugin.view.AwtChord
import com.letta.mobile.desktop.plugin.view.cefChord
import com.letta.mobile.ui.shell.shortcuts.ShellShortcutBinding
import com.letta.mobile.ui.shell.shortcuts.ShellShortcutId
import com.letta.mobile.ui.shell.shortcuts.ShortcutFocusOwner
import com.letta.mobile.ui.shell.shortcuts.ShortcutHub
import com.letta.mobile.ui.shell.shortcuts.ShortcutPlatform
import com.letta.mobile.ui.shell.shortcuts.WorkspaceShortcuts
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel
import javax.swing.JTextField
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-k5nbq.11: chords typed inside SwingPanel and JCEF views reach the shared keymap. */
class DesktopShortcutsTest {
    @Test
    fun keysOnTheComposeLayerAreLeftToCompose() {
        assertNull(swingFocusOwner(JPanel()))
        assertNull(swingFocusOwner(null))
    }

    @Test
    fun embeddedSwingViewsAreForeignFocusOwners() {
        val view = JPanel()
        val field = JTextField()
        foreignHost().add(view)
        view.add(field)
        assertEquals(ShortcutFocusOwner.ForeignView, swingFocusOwner(view))
        assertEquals(ShortcutFocusOwner.ForeignTextField, swingFocusOwner(field))
    }

    @Test
    fun aChordInsideAnEmbeddedViewRunsTheBoundShortcut() {
        val fired = mutableListOf<ShellShortcutId>()
        val hub = ShortcutHub()
        hub.binding = ShellShortcutBinding(ShortcutPlatform.Desktop, WorkspaceShortcuts) { fired += it }
        val view = JPanel()
        foreignHost().add(view)
        assertTrue(dispatchSwingShortcut(press(view, KeyEvent.VK_B), hub))
        assertFalse(dispatchSwingShortcut(press(JPanel(), KeyEvent.VK_B), hub), "Compose matches its own layer")
        assertEquals(listOf(ShellShortcutId.ToggleSidebar), fired)
    }

    private fun foreignHost(): JPanel = JPanel().apply {
        putClientProperty(FOREIGN_SWING_VIEW_PROP, true)
    }

    @Test
    fun cefKeyCodesBecomeAwtChords() {
        val ctrl = 1 shl 2
        val shift = 1 shl 1
        assertEquals(AwtChord(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK), cefChord('K'.code, ctrl))
        assertEquals(
            AwtChord(KeyEvent.VK_CLOSE_BRACKET, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
            cefChord(0xDD, ctrl or shift),
        )
        assertEquals(AwtChord(KeyEvent.VK_COMMA, InputEvent.CTRL_DOWN_MASK), cefChord(0xBC, ctrl))
        assertNull(cefChord('K'.code, shift), "typing is not a chord")
    }

    private fun press(source: java.awt.Component, keyCode: Int) = KeyEvent(
        source,
        KeyEvent.KEY_PRESSED,
        0L,
        InputEvent.CTRL_DOWN_MASK,
        keyCode,
        KeyEvent.CHAR_UNDEFINED,
        KeyEvent.KEY_LOCATION_STANDARD,
    )
}
