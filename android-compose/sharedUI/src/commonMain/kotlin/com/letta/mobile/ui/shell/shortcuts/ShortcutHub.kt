package com.letta.mobile.ui.shell.shortcuts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import com.letta.mobile.ui.chat.surface.composer.ComposerFocusHandoff

/** Where keyboard focus sits when a stroke reaches the hub. */
enum class ShortcutFocusOwner {
    /** A node of the shared Compose tree; the hub's own focus facts apply. */
    Shell,

    /** A text field the host embeds outside the Compose tree (a Swing field on desktop). */
    ForeignTextField,

    /** Any other embedded view outside the Compose tree (a JCEF page, a SwingPanel). */
    ForeignView,
}

/**
 * Window-level shortcut focus: the host's [binding], which prompt to focus, and whether a
 * non-composer text field is holding the caret (so shell chords stay out of rename/search).
 */
@Stable
class ShortcutHub {
    internal var composer: ComposerFocusHandoff? by mutableStateOf(null)

    /** The keymap binding of the host this hub serves; null until the host installs one. */
    var binding: ShellShortcutBinding? by mutableStateOf(null)

    var textInputs: Int by mutableIntStateOf(0)
        private set

    val composerFocused: Boolean get() = composer?.focused == true

    val inTextInput: Boolean get() = textInputs > 0

    /**
     * Runs [stroke] through the bound keymap and reports whether it was consumed. A chord whose
     * entry the host does not cover, or a composer chord with no composer on the page, is not.
     */
    fun dispatch(stroke: ShortcutKeystroke, owner: ShortcutFocusOwner = ShortcutFocusOwner.Shell): Boolean {
        val active = binding ?: return false
        val id = ShortcutRegistry.match(stroke, contextFor(active.platform, owner), active::covers) ?: return false
        return when (id) {
            // The prompt's own key handler cycles the permission mode.
            ShellShortcutId.CyclePermission -> false
            ShellShortcutId.FocusComposer -> focusComposer()
            else -> {
                active.onShortcut(id)
                true
            }
        }
    }

    private fun focusComposer(): Boolean {
        val prompt = composer ?: return false
        prompt.requestFocus()
        return true
    }

    private fun contextFor(platform: ShortcutPlatform, owner: ShortcutFocusOwner): ShortcutContext = when (owner) {
        ShortcutFocusOwner.Shell -> ShortcutContext(platform, inTextInput, composerFocused)
        ShortcutFocusOwner.ForeignTextField -> ShortcutContext(platform, inTextInput = true, composerFocused = false)
        ShortcutFocusOwner.ForeignView -> ShortcutContext(platform, inTextInput = false, composerFocused = false)
    }

    internal fun acquireTextInput() {
        textInputs += 1
    }

    internal fun releaseTextInput() {
        textInputs = (textInputs - 1).coerceAtLeast(0)
    }
}

val LocalShortcutHub = staticCompositionLocalOf { ShortcutHub() }

@Composable
fun ProvideShortcutHub(content: @Composable () -> Unit) {
    val hub = remember { ShortcutHub() }
    CompositionLocalProvider(LocalShortcutHub provides hub, content = content)
}

/** Counts this field as a text input the shell chords must not steal from. */
fun Modifier.shortcutTextInput(): Modifier = composed {
    val hub = LocalShortcutHub.current
    var held by remember { mutableStateOf(false) }
    DisposableEffect(hub) {
        onDispose { if (held) hub.releaseTextInput() }
    }
    onFocusChanged { state ->
        when {
            state.isFocused && !held -> {
                held = true
                hub.acquireTextInput()
            }
            !state.isFocused && held -> {
                held = false
                hub.releaseTextInput()
            }
        }
    }
}

/** Binds the chat page's prompt to the window hub for the lifetime of the page. */
@Composable
internal fun BindComposerShortcuts(handoff: ComposerFocusHandoff) {
    val hub = LocalShortcutHub.current
    DisposableEffect(hub, handoff) {
        hub.composer = handoff
        onDispose { if (hub.composer === handoff) hub.composer = null }
    }
}
