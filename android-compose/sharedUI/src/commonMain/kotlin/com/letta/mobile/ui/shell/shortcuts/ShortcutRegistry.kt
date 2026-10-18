package com.letta.mobile.ui.shell.shortcuts

import androidx.compose.ui.input.key.Key

/** Group titles the help overlay sections by. */
object ShortcutGroups {
    const val GENERAL = "General"
    const val CHAT = "Chat"
    const val AGENTS = "Agents"
    const val PAGES = "Pages"
    const val VIEW = "View"
}

/**
 * letta-mobile-k5nbq.4: the one keymap every host matches. Platform modules do not register
 * their own in-window handlers; OS-global hotkeys (the desktop quick switcher) stay on the host.
 */
object ShortcutRegistry {
    val catalog: List<ShellShortcut> = listOf(
        reserved(ShellShortcutId.Help, "Keyboard shortcuts", ShortcutGroups.GENERAL, Key.P),
        entry(ShellShortcutId.Search, "Search", ShortcutGroups.GENERAL, mod(Key.K)),
        entry(ShellShortcutId.Find, "Find in conversation", ShortcutGroups.GENERAL, mod(Key.F)),
        entry(ShellShortcutId.QuickOpen, "Quick open", ShortcutGroups.GENERAL, mod(Key.O, shift = true)),
        entry(ShellShortcutId.Settings, "Settings", ShortcutGroups.GENERAL, mod(Key.Comma)),
        reserved(ShellShortcutId.FocusComposer, "Focus composer", ShortcutGroups.CHAT, Key.L),
        reserved(ShellShortcutId.NewConversation, "New conversation", ShortcutGroups.CHAT, Key.N),
        entry(ShellShortcutId.Rename, "Rename chat", ShortcutGroups.CHAT, mod(Key.R, alt = true)),
        ShellShortcut(
            id = ShellShortcutId.CyclePermission,
            label = "Cycle permission mode",
            group = ShortcutGroups.CHAT,
            binding = ShortcutBinding(
                chord = ShortcutChord(Key.Tab, ShortcutModifiers(shift = true)),
                scope = ShortcutScope.Composer,
            ),
        ),
        entry(ShellShortcutId.PreviousConversation, "Previous conversation", ShortcutGroups.CHAT, mod(Key.DirectionUp, shift = true)),
        entry(ShellShortcutId.NextConversation, "Next conversation", ShortcutGroups.CHAT, mod(Key.DirectionDown, shift = true)),
        entry(ShellShortcutId.PreviousAgent, "Previous agent", ShortcutGroups.AGENTS, mod(Key.LeftBracket, shift = true)),
        entry(ShellShortcutId.NextAgent, "Next agent", ShortcutGroups.AGENTS, mod(Key.RightBracket, shift = true)),
        entry(ShellShortcutId.Memory, "Memory", ShortcutGroups.PAGES, mod(Key.M)),
        entry(ShellShortcutId.FullscreenMemory, "Memory (full)", ShortcutGroups.PAGES, mod(Key.M, shift = true)),
        entry(ShellShortcutId.Schedules, "Schedules", ShortcutGroups.PAGES, mod(Key.Y, shift = true)),
        entry(ShellShortcutId.Filesystem, "Filesystem", ShortcutGroups.PAGES, mod(Key.E, shift = true)),
        entry(ShellShortcutId.FileTree, "File tree", ShortcutGroups.PAGES, mod(Key.B)),
        entry(ShellShortcutId.Terminal, "Terminal", ShortcutGroups.PAGES, mod(Key.Grave)),
        entry(ShellShortcutId.ToggleSidebar, "Toggle sidebar", ShortcutGroups.VIEW, mod(Key.Slash, shift = true)),
    )

    /** The first catalog entry that matches [stroke] on [context], after the composer-focus rule. */
    fun match(stroke: ShortcutKeystroke, context: ShortcutContext): ShellShortcutId? {
        if (!stroke.down) return null
        val hit = catalog.firstOrNull { it.matches(stroke, context.platform) } ?: return null
        return if (shortcutAllowed(hit.scope, context)) hit.id else null
    }

    /** Catalog entries grouped in overlay order. */
    fun grouped(): List<Pair<String, List<ShellShortcut>>> {
        val order = listOf(ShortcutGroups.GENERAL, ShortcutGroups.CHAT, ShortcutGroups.AGENTS, ShortcutGroups.PAGES, ShortcutGroups.VIEW)
        val byGroup = catalog.groupBy { it.group }
        return order.mapNotNull { group -> byGroup[group]?.let { group to it } }
    }
}

/** Ctrl/Cmd chord; [shift] and [alt] add those modifiers. */
internal fun mod(key: Key, shift: Boolean = false, alt: Boolean = false): ShortcutChord =
    ShortcutChord(key, ShortcutModifiers(ctrlOrMeta = true, shift = shift, alt = alt))

private fun entry(id: ShellShortcutId, label: String, group: String, chord: ShortcutChord): ShellShortcut =
    ShellShortcut(id, label, group, ShortcutBinding(chord))

private fun reserved(id: ShellShortcutId, label: String, group: String, key: Key): ShellShortcut =
    ShellShortcut(id, label, group, ShortcutBinding(mod(key), webAlternate = mod(key, shift = true)))

internal fun ShellShortcut.matches(stroke: ShortcutKeystroke, platform: ShortcutPlatform): Boolean =
    chordOn(platform).matches(stroke)

internal fun ShortcutChord.matches(stroke: ShortcutKeystroke): Boolean =
    key == stroke.key && modifiers == stroke.modifiers

/**
 * Shell chords are ignored in text inputs other than the composer; composer-scoped chords
 * fire only while the prompt has focus.
 */
internal fun shortcutAllowed(scope: ShortcutScope, context: ShortcutContext): Boolean {
    if (scope == ShortcutScope.Composer) return context.composerFocused
    return !context.inTextInput || context.composerFocused
}
