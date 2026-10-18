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

/**
 * Window-level shortcut focus: which prompt to focus, and whether a non-composer text field is
 * holding the caret (so shell chords stay out of rename/search).
 */
@Stable
class ShortcutHub {
    internal var composer: ComposerFocusHandoff? by mutableStateOf(null)

    var textInputs: Int by mutableIntStateOf(0)
        private set

    val composerFocused: Boolean get() = composer?.focused == true

    val inTextInput: Boolean get() = textInputs > 0

    fun context(platform: ShortcutPlatform): ShortcutContext =
        ShortcutContext(platform, inTextInput, composerFocused)

    fun requestComposer() {
        composer?.requestFocus()
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

