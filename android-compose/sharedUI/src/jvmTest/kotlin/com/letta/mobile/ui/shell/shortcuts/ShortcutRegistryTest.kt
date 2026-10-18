package com.letta.mobile.ui.shell.shortcuts

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-k5nbq.4: chord → intent mapping and the composer-focus suppression rule. */
class ShortcutRegistryTest {
    @Test
    fun desktopChordsMapToTheOfficialIntents() {
        val desktop = ctx(ShortcutPlatform.Desktop)
        assertEquals(ShellShortcutId.Search, match(Key.K, desktop))
        assertEquals(ShellShortcutId.NewConversation, match(Key.N, desktop))
        assertEquals(ShellShortcutId.FocusComposer, match(Key.L, desktop))
        assertEquals(ShellShortcutId.Help, match(Key.P, desktop))
        assertEquals(ShellShortcutId.Settings, match(Key.Comma, desktop))
        assertEquals(ShellShortcutId.Memory, match(Key.M, desktop))
        assertEquals(ShellShortcutId.Schedules, match(Key.Y, desktop, shift = true))
        assertEquals(ShellShortcutId.ToggleSidebar, match(Key.Slash, desktop, shift = true))
        assertEquals(ShellShortcutId.PreviousConversation, match(Key.DirectionUp, desktop, shift = true))
        assertEquals(ShellShortcutId.NextConversation, match(Key.DirectionDown, desktop, shift = true))
        assertEquals(ShellShortcutId.PreviousAgent, match(Key.LeftBracket, desktop, shift = true))
        assertEquals(ShellShortcutId.NextAgent, match(Key.RightBracket, desktop, shift = true))
    }

    @Test
    fun webUsesAlternatesForBrowserReservedChords() {
        val web = ctx(ShortcutPlatform.Web)
        assertNull(match(Key.N, web), "Ctrl+N is New Tab in the browser")
        assertEquals(ShellShortcutId.NewConversation, match(Key.N, web, shift = true))
        assertNull(match(Key.L, web), "Ctrl+L is the address bar")
        assertEquals(ShellShortcutId.FocusComposer, match(Key.L, web, shift = true))
        assertNull(match(Key.P, web), "Ctrl+P is Print")
        assertEquals(ShellShortcutId.Help, match(Key.P, web, shift = true))
        assertEquals(ShellShortcutId.Search, match(Key.K, web), "Ctrl+K is free in the browser")
    }

    @Test
    fun shellChordsAreIgnoredInTextInputsOtherThanTheComposer() {
        val typing = ctx(inTextInput = true, composerFocused = false)
        assertNull(match(Key.K, typing))
        assertNull(match(Key.N, typing))
        val atPrompt = ctx(inTextInput = false, composerFocused = true)
        assertEquals(ShellShortcutId.Search, match(Key.K, atPrompt))
        val promptAndField = ctx(inTextInput = true, composerFocused = true)
        assertEquals(ShellShortcutId.Search, match(Key.K, promptAndField))
    }

    @Test
    fun shiftTabIsComposerScoped() {
        val tab = ShortcutKeystroke(Key.Tab, down = true, ShortcutModifiers(shift = true))
        assertNull(ShortcutRegistry.match(tab, ctx(composerFocused = false)))
        assertEquals(ShellShortcutId.CyclePermission, ShortcutRegistry.match(tab, ctx(composerFocused = true)))
    }

    @Test
    fun helpOverlayListsEveryCatalogEntry() {
        val labels = ShortcutRegistry.grouped().flatMap { it.second }.map { it.id }
        assertEquals(ShortcutRegistry.catalog.map { it.id }, labels)
        assertTrue(ShortcutRegistry.catalog.any { it.id == ShellShortcutId.Help })
    }
}

private fun ctx(
    platform: ShortcutPlatform = ShortcutPlatform.Desktop,
    inTextInput: Boolean = false,
    composerFocused: Boolean = false,
) = ShortcutContext(platform, inTextInput, composerFocused)

private fun match(
    key: Key,
    context: ShortcutContext,
    shift: Boolean = false,
    alt: Boolean = false,
): ShellShortcutId? = ShortcutRegistry.match(
    ShortcutKeystroke(key, down = true, ShortcutModifiers(ctrlOrMeta = true, shift = shift, alt = alt)),
    context,
)
