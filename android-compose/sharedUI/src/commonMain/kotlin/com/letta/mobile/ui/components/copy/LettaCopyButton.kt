package com.letta.mobile.ui.components.copy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_copied
import com.letta.mobile.ui.components.foundation.LettaGlyphButton
import com.letta.mobile.ui.components.foundation.LettaIcon
import com.letta.mobile.ui.components.foundation.LettaIconStyle
import com.letta.mobile.ui.components.foundation.StatusGlyph
import com.letta.mobile.ui.components.line.StatusTone
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.text.writePlainText
import com.letta.mobile.ui.theme.tokens.LettaFeedbackMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The one Copy button: puts [text] on the window's clipboard as plain text, then shows the success
 * check and says "Copied" (politely, to a screen reader) for [LettaFeedbackMotion.COPIED_MILLIS].
 * [contentDescription] names what it copies ("Copy code", "Copy link"). It is an icon button, so Tab
 * reaches it and Enter or Space copies. [glyph] sets the glyph's size and its resting tint; the check
 * keeps the success tint.
 */
@Composable
internal fun LettaCopyButton(
    text: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    glyph: LettaIconStyle = LettaIconStyle(),
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(LettaFeedbackMotion.COPIED_MILLIS)
            copied = false
        }
    }
    val label = if (copied) stringResource(Res.string.rows_copied) else contentDescription
    LettaGlyphButton(
        onClick = {
            scope.launch {
                clipboard.writePlainText(text)
                copied = true
            }
        },
        modifier = modifier.semantics {
            this.contentDescription = label
            liveRegion = LiveRegionMode.Polite
        },
    ) {
        if (copied) {
            StatusGlyph(StatusTone.Success, size = glyph.size)
        } else {
            LettaIcon(LettaIcons.Copy, contentDescription = null, style = glyph)
        }
    }
}
