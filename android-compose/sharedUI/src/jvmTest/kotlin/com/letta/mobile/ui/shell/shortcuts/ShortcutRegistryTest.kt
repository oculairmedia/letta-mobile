package com.letta.mobile.ui.shell.shortcuts

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-k5nbq.4 / .11: chord → intent mapping, host coverage and the focus suppression rule. */
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
        assertEquals(ShellShortcutId.ToggleSidebar, match(Key.B, desktop))
        assertEquals(ShellShortcutId.PreviousConversation, match(Key.DirectionUp, desktop, shift = true))
        assertEquals(ShellShortcutId.NextConversation, match(Key.DirectionDown, desktop, shift = true))
        assertEquals(ShellShortcutId.PreviousAgent, match(Key.LeftBracket, desktop, shift = true))
        assertEquals(ShellShortcutId.NextAgent, match(Key.RightBracket, desktop, shift = true))
    }

    @Test
    fun webAlternatesAvoidEveryBrowserReservedChord() {
        val web = ctx(ShortcutPlatform.Web)
        assertNull(match(Key.N, web), "Ctrl+N is New Tab in the browser")
        assertEquals(ShellShortcutId.NewConversation, match(Key.N, web, alt = true))
        assertNull(match(Key.L, web), "Ctrl+L is the address bar")
        assertEquals(ShellShortcutId.FocusComposer, match(Key.L, web, shift = true))
        assertNull(match(Key.P, web), "Ctrl+P is Print")
        assertEquals(ShellShortcutId.Help, match(Key.Slash, web))
        assertEquals(ShellShortcutId.Search, match(Key.K, web), "Ctrl+K is free in the browser")
        // Pages never receive these: Chrome's incognito window, Firefox's private window, new window, tabs.
        val browserOwned = listOf(
            mod(Key.N, shift = true), mod(Key.P, shift = true), mod(Key.T, shift = true), mod(Key.W, shift = true),
            mod(Key.N), mod(Key.T), mod(Key.W),
        )
        val webChords = ShortcutRegistry.catalog.map { it.chordOn(ShortcutPlatform.Web) }
        browserOwned.forEach { owned -> assertNull(webChords.firstOrNull { it == owned }, "web binds browser-owned ${owned.label()}") }
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
    fun editingChordsReachTheComposer() {
        val atPrompt = ctx(composerFocused = true)
        assertNull(match(Key.DirectionUp, atPrompt, shift = true), "Ctrl+Shift+Up extends the selection")
        assertNull(match(Key.DirectionDown, atPrompt, shift = true), "Ctrl+Shift+Down extends the selection")
        assertNull(match(Key.B, atPrompt), "Ctrl+B belongs to the field")
        assertNull(match(Key.F, atPrompt), "Ctrl+F belongs to the field")
        assertEquals(ShellShortcutId.Find, match(Key.F, ctx()))
    }

    @Test
    fun aChordTheHostDoesNotCoverMatchesNothing() {
        val withoutSidebar = ShortcutRegistry.match(stroke(Key.B), ctx()) { it != ShellShortcutId.ToggleSidebar }
        assertNull(withoutSidebar)
    }

    @Test
    fun shiftTabIsComposerScoped() {
        val tab = ShortcutKeystroke(Key.Tab, down = true, ShortcutModifiers(shift = true))
        assertNull(ShortcutRegistry.match(tab, ctx(composerFocused = false), everything))
        assertEquals(ShellShortcutId.CyclePermission, ShortcutRegistry.match(tab, ctx(composerFocused = true), everything))
    }

    @Test
    fun helpListsOnlyWhatTheHostCovers() {
        val android = setOf(ShellShortcutId.Help, ShellShortcutId.Memory)
        val listed = ShortcutRegistry.grouped { it in android }.flatMap { it.second }.map { it.id }
        assertEquals(listOf(ShellShortcutId.Help, ShellShortcutId.Memory), listed)
        assertEquals(ShortcutRegistry.catalog.map { it.id }, ShortcutRegistry.grouped(everything).flatMap { it.second }.map { it.id })
    }
}

private val everything: (ShellShortcutId) -> Boolean = { true }

private fun ctx(
    platform: ShortcutPlatform = ShortcutPlatform.Desktop,
    inTextInput: Boolean = false,
    composerFocused: Boolean = false,
) = ShortcutContext(platform, inTextInput, composerFocused)

private fun stroke(key: Key, shift: Boolean = false, alt: Boolean = false) =
    ShortcutKeystroke(key, down = true, ShortcutModifiers(ctrlOrMeta = true, shift = shift, alt = alt))

private fun match(
    key: Key,
    context: ShortcutContext,
    shift: Boolean = false,
    alt: Boolean = false,
): ShellShortcutId? = ShortcutRegistry.match(stroke(key, shift, alt), context, everything)
