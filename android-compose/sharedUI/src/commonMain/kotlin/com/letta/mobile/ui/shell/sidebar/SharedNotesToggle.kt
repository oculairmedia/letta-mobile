package com.letta.mobile.ui.shell.sidebar

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * The person's way to show or hide the shell's shared-notes view.
 * @property shown The layout shows the view now; the toggle then hides it.
 * @property toggle Shows the view when hidden, hides it when shown.
 */
@Immutable
class SharedNotesToggle(val shown: Boolean, val toggle: () -> Unit)

/**
 * The shared-notes toggle the workspace provides to its menus: null while the host cannot reach
 * shared notes or no shell layout is attached, so the menus offer nothing that would not work.
 */
val LocalSharedNotesToggle = compositionLocalOf<SharedNotesToggle?> { null }
