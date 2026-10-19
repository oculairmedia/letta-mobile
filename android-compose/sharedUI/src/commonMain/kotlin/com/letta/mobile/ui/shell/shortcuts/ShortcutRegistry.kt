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
 * The one keymap every host matches. Platform modules do not register
 * their own in-window handlers; OS-global hotkeys (the desktop quick switcher) stay on the host.
 */
object ShortcutRegistry {
    val catalog: List<ShellShortcut> = listOf(
        // Ctrl+Shift+P is Firefox's private window and Ctrl+Shift+N Chrome's incognito one; pages
        // cannot take either, so the web alternates avoid Shift there.
        reserved(ShellShortcutId.Help, "Keyboard shortcuts", ShortcutGroups.GENERAL, Key.P, webAlternate = mod(Key.Slash)),
        entry(ShellShortcutId.Search, "Search", ShortcutGroups.GENERAL, mod(Key.K)),
        entry(ShellShortcutId.Find, "Find in conversation", ShortcutGroups.GENERAL, mod(Key.F), ShortcutScope.OutsideText),
        entry(ShellShortcutId.QuickOpen, "Quick open", ShortcutGroups.GENERAL, mod(Key.O, shift = true)),
        entry(ShellShortcutId.Settings, "Settings", ShortcutGroups.GENERAL, mod(Key.Comma)),
        reserved(ShellShortcutId.FocusComposer, "Focus composer", ShortcutGroups.CHAT, Key.L, webAlternate = mod(Key.L, shift = true)),
        reserved(ShellShortcutId.NewConversation, "New conversation", ShortcutGroups.CHAT, Key.N, webAlternate = mod(Key.N, alt = true)),
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
        entry(
            ShellShortcutId.PreviousConversation, "Previous conversation", ShortcutGroups.CHAT,
            mod(Key.DirectionUp, shift = true), ShortcutScope.OutsideText,
        ),
        entry(
            ShellShortcutId.NextConversation, "Next conversation", ShortcutGroups.CHAT,
            mod(Key.DirectionDown, shift = true), ShortcutScope.OutsideText,
        ),
        entry(ShellShortcutId.PreviousAgent, "Previous agent", ShortcutGroups.AGENTS, mod(Key.LeftBracket, shift = true)),
        entry(ShellShortcutId.NextAgent, "Next agent", ShortcutGroups.AGENTS, mod(Key.RightBracket, shift = true)),
        entry(ShellShortcutId.Memory, "Memory", ShortcutGroups.PAGES, mod(Key.M)),
        entry(ShellShortcutId.FullscreenMemory, "Memory (full)", ShortcutGroups.PAGES, mod(Key.M, shift = true)),
        entry(ShellShortcutId.Schedules, "Schedules", ShortcutGroups.PAGES, mod(Key.Y, shift = true)),
        entry(ShellShortcutId.ToggleSidebar, "Toggle sidebar", ShortcutGroups.VIEW, mod(Key.B), ShortcutScope.OutsideText),
    )

    /** Entries the shared chat page performs itself, so every host with a composer has them. */
    val composerOwned: Set<ShellShortcutId> = setOf(ShellShortcutId.FocusComposer, ShellShortcutId.CyclePermission)

    /**
     * The first entry [covers] accepts that matches [stroke] on [context], after the focus rule.
     * A chord whose entry the host does not cover matches nothing, so the key reaches the field.
     */
    fun match(stroke: ShortcutKeystroke, context: ShortcutContext, covers: (ShellShortcutId) -> Boolean): ShellShortcutId? {
        if (!stroke.down) return null
        val hit = catalog.firstOrNull { covers(it.id) && it.matches(stroke, context.platform) } ?: return null
        return if (shortcutAllowed(hit.scope, context)) hit.id else null
    }

    /** The entries [covers] accepts, grouped in overlay order. */
    fun grouped(covers: (ShellShortcutId) -> Boolean): List<Pair<String, List<ShellShortcut>>> {
        val order = listOf(ShortcutGroups.GENERAL, ShortcutGroups.CHAT, ShortcutGroups.AGENTS, ShortcutGroups.PAGES, ShortcutGroups.VIEW)
        val byGroup = catalog.filter { covers(it.id) }.groupBy { it.group }
        return order.mapNotNull { group -> byGroup[group]?.let { group to it } }
    }
}

/** Ctrl/Cmd chord; [shift] and [alt] add those modifiers. */
internal fun mod(key: Key, shift: Boolean = false, alt: Boolean = false): ShortcutChord =
    ShortcutChord(key, ShortcutModifiers(ctrlOrMeta = true, shift = shift, alt = alt))

private fun entry(
    id: ShellShortcutId,
    label: String,
    group: String,
    chord: ShortcutChord,
    scope: ShortcutScope = ShortcutScope.Shell,
): ShellShortcut = ShellShortcut(id, label, group, ShortcutBinding(chord, scope = scope))

/** Ctrl/Cmd+[key], which the browser keeps for itself; the web page binds [webAlternate] instead. */
private fun reserved(id: ShellShortcutId, label: String, group: String, key: Key, webAlternate: ShortcutChord): ShellShortcut =
    ShellShortcut(id, label, group, ShortcutBinding(mod(key), webAlternate = webAlternate))

internal fun ShellShortcut.matches(stroke: ShortcutKeystroke, platform: ShortcutPlatform): Boolean =
    chordOn(platform).matches(stroke)

internal fun ShortcutChord.matches(stroke: ShortcutKeystroke): Boolean =
    key == stroke.key && modifiers == stroke.modifiers

/**
 * Shell chords are ignored in text inputs other than the composer; outside-text chords in every
 * text input; composer-scoped chords fire only while the prompt has focus.
 */
internal fun shortcutAllowed(scope: ShortcutScope, context: ShortcutContext): Boolean = when (scope) {
    ShortcutScope.Composer -> context.composerFocused
    ShortcutScope.OutsideText -> !context.inTextInput && !context.composerFocused
    ShortcutScope.Shell -> !context.inTextInput || context.composerFocused
}
