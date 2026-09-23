package com.letta.mobile.ui.canvas

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Which document's editor should take the caret next.
 *
 * Activating a note or a shape's label only made its editor editable; nothing put the caret in
 * it, so on a phone the keyboard never came up and a freshly drawn shape could not simply be
 * typed into. The board names the document here and that document's editor, once it is on screen
 * and active, focuses itself and clears the request.
 */
class CanvasFocusRequest {
    var documentId: String? by mutableStateOf(null)
}

/** The board's focus request, for editors composed inside it; null outside a board. */
val LocalCanvasFocusRequest = compositionLocalOf<CanvasFocusRequest?> { null }
