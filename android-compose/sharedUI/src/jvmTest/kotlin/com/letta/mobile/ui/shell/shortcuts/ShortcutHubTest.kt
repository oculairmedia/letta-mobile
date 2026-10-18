package com.letta.mobile.ui.shell.shortcuts

import androidx.compose.ui.input.key.Key
import com.letta.mobile.ui.chat.surface.composer.ComposerFocusHandoff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** letta-mobile-k5nbq.11: what the window hub consumes, and what it leaves for the focused field. */
class ShortcutHubTest {
    private val fired = mutableListOf<ShellShortcutId>()

    private fun hub(bound: Set<ShellShortcutId> = WorkspaceShortcuts) = ShortcutHub().apply {
        binding = ShellShortcutBinding(ShortcutPlatform.Desktop, bound) { fired += it }
    }

    @Test
    fun aHostWithoutABindingConsumesNothing() {
        assertFalse(ShortcutHub().dispatch(chord(Key.K)))
    }

    @Test
    fun aBoundChordIsConsumedAndRun() {
        assertTrue(hub().dispatch(chord(Key.K)))
        assertEquals(listOf(ShellShortcutId.Search), fired)
    }

    @Test
    fun aChordTheHostDoesNotPerformPassesThrough() {
        val android = hub(setOf(ShellShortcutId.Help, ShellShortcutId.Memory))
        assertFalse(android.dispatch(chord(Key.K)), "no palette: Ctrl+K reaches the field")
        assertFalse(android.dispatch(chord(Key.R, alt = true)), "no rename dialog")
        assertTrue(fired.isEmpty())
    }

    @Test
    fun editingChordsInTheComposerReachTheField() {
        val hub = hub()
        hub.composer = ComposerFocusHandoff().apply { focused = true }
        assertFalse(hub.dispatch(chord(Key.B)))
        assertFalse(hub.dispatch(chord(Key.F)))
        assertFalse(hub.dispatch(chord(Key.DirectionUp, shift = true)))
        assertTrue(hub.dispatch(chord(Key.K)), "search still opens from the prompt")
        assertEquals(listOf(ShellShortcutId.Search), fired)
    }

    @Test
    fun focusComposerIsConsumedOnlyWhenThePageHasOne() {
        val hub = hub()
        assertFalse(hub.dispatch(chord(Key.L)))
        hub.composer = ComposerFocusHandoff()
        assertTrue(hub.dispatch(chord(Key.L)))
        assertTrue(fired.isEmpty(), "the hub focuses the prompt itself")
    }

    @Test
    fun shiftTabStaysWithThePrompt() {
        val hub = hub()
        hub.composer = ComposerFocusHandoff().apply { focused = true }
        assertFalse(hub.dispatch(ShortcutKeystroke(Key.Tab, down = true, ShortcutModifiers(shift = true))))
    }

    @Test
    fun foreignTextFieldsKeepShellChordsAndForeignViewsDoNot() {
        val hub = hub()
        assertFalse(hub.dispatch(chord(Key.K), ShortcutFocusOwner.ForeignTextField))
        assertTrue(hub.dispatch(chord(Key.B), ShortcutFocusOwner.ForeignView))
        assertEquals(listOf(ShellShortcutId.ToggleSidebar), fired)
    }
}

private fun chord(key: Key, shift: Boolean = false, alt: Boolean = false) =
    ShortcutKeystroke(key, down = true, ShortcutModifiers(ctrlOrMeta = true, shift = shift, alt = alt))
