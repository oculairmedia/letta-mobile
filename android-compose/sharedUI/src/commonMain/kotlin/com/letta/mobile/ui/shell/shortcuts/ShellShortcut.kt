package com.letta.mobile.ui.shell.shortcuts

import androidx.compose.ui.input.key.Key

/** Host the shortcut catalog is matching for (browser-reserved chords differ on web). */
enum class ShortcutPlatform { Desktop, Web, Android }

/** Whether a chord fires while a text field has focus. */
enum class ShortcutScope {
    /** Ignored in text inputs other than the composer. */
    Shell,

    /**
     * Ignored in every text input, the composer too: the field owns these chords (selection
     * extension on Ctrl+Shift+Up/Down, bold on Ctrl+B, find on Ctrl+F).
     */
    OutsideText,

    /** Only while the composer has focus (Shift+Tab permission cycle). */
    Composer,
}

/** One command the shared keymap can fire. */
enum class ShellShortcutId {
    Help,
    FocusComposer,
    Search,
    NewConversation,
    Memory,
    FullscreenMemory,
    Find,
    Rename,
    PreviousConversation,
    NextConversation,
    PreviousAgent,
    NextAgent,
    CyclePermission,
    ToggleSidebar,
    QuickOpen,
    Schedules,
    Settings,
}

/** Modifier bits of a chord. [ctrlOrMeta] is Ctrl on Windows/Linux/Android and Cmd on macOS. */
data class ShortcutModifiers(
    val ctrlOrMeta: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
)

/** One key plus its modifiers. */
data class ShortcutChord(
    val key: Key,
    val modifiers: ShortcutModifiers = ShortcutModifiers(ctrlOrMeta = true),
)

/** A key event reduced to what the registry matches. */
data class ShortcutKeystroke(
    val key: Key,
    val down: Boolean,
    val modifiers: ShortcutModifiers,
)

/** Focus facts the suppression rule reads. */
data class ShortcutContext(
    val platform: ShortcutPlatform,
    val inTextInput: Boolean,
    val composerFocused: Boolean,
)

/** Chord, optional browser-reserved alternate, and text-field scope. */
data class ShortcutBinding(
    val chord: ShortcutChord,
    val webAlternate: ShortcutChord? = null,
    val scope: ShortcutScope = ShortcutScope.Shell,
)

/**
 * One entry in the shared keymap. [ShortcutBinding.webAlternate] replaces
 * the primary chord on [ShortcutPlatform.Web] when the browser already owns it (N, L, P).
 */
data class ShellShortcut(
    val id: ShellShortcutId,
    val label: String,
    val group: String,
    val binding: ShortcutBinding,
) {
    val chord: ShortcutChord get() = binding.chord
    val webAlternate: ShortcutChord? get() = binding.webAlternate
    val scope: ShortcutScope get() = binding.scope
}

/** The chord this entry uses on [platform]. */
fun ShellShortcut.chordOn(platform: ShortcutPlatform): ShortcutChord {
    val alternate = webAlternate
    return if (platform == ShortcutPlatform.Web && alternate != null) alternate else chord
}

/**
 * A host's binding of the keymap: the [bound] verbs it actually performs through [onShortcut].
 * A chord for any other entry is left to the focused field and is not listed in the help overlay,
 * except the composer-owned entries ([ShortcutRegistry.composerOwned]) every chat page provides.
 */
class ShellShortcutBinding(
    val platform: ShortcutPlatform,
    val bound: Set<ShellShortcutId>,
    val onShortcut: (ShellShortcutId) -> Unit,
) {
    fun covers(id: ShellShortcutId): Boolean = id in bound || id in ShortcutRegistry.composerOwned
}
