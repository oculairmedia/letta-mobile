package com.letta.mobile.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.markdown_code_copied
import com.letta.mobile.sharedui.resources.markdown_copy_code
import com.letta.mobile.ui.components.foundation.LettaButtonIcon
import com.letta.mobile.ui.components.foundation.LettaIconButton
import com.letta.mobile.ui.components.foundation.LettaIconStyle
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.text.writePlainText
import com.letta.mobile.ui.theme.tokens.LettaControlTokens
import com.letta.mobile.ui.theme.tokens.LettaSpaceTokens
import com.letta.mobile.ui.theme.tokens.LettaStrokeTokens
import com.mikepenz.markdown.compose.LocalMarkdownColors
import com.mikepenz.markdown.compose.LocalMarkdownDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** How long the copy button shows its check after a copy. */
private const val COPIED_FEEDBACK_MILLIS: Long = 1_500L

/**
 * A code fence with its header: the fence's [language] (when it names one) and a Copy that puts
 * [source] (the code alone, without the fence) on the clipboard, then shows a check for a moment and
 * says "Copied". [block] is the code itself, drawn as the renderer draws it.
 */
@Composable
internal fun CodeFenceWithHeader(language: String, source: String, block: @Composable () -> Unit) {
    // The header sits on the block's own fill and corners, so header and code read as one piece.
    val shape = RoundedCornerShape(LocalMarkdownDimens.current.codeBackgroundCornerSize)
    Column(Modifier.fillMaxWidth().clip(shape).background(LocalMarkdownColors.current.codeBackground)) {
        CodeFenceHeader(language, source)
        HorizontalDivider(thickness = LettaStrokeTokens.hairline, color = MaterialTheme.colorScheme.outlineVariant)
        block()
    }
}

@Composable
private fun CodeFenceHeader(language: String, source: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = LettaSpaceTokens.sm, end = LettaSpaceTokens.xs)
            .testTag(CodeFenceTags.HEADER),
        horizontalArrangement = Arrangement.spacedBy(LettaSpaceTokens.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = language,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag(CodeFenceTags.LANGUAGE),
        )
        CopyCodeButton(source)
    }
}

@Composable
private fun CopyCodeButton(source: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_FEEDBACK_MILLIS)
            copied = false
        }
    }
    val label = stringResource(if (copied) Res.string.markdown_code_copied else Res.string.markdown_copy_code)
    val icon = if (copied) LettaIcons.Check else LettaIcons.Copy
    val tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    LettaIconButton(
        LettaButtonIcon(icon, label, size = LettaControlTokens.iconButton, glyph = LettaIconStyle(tint = tint)),
        onClick = {
            scope.launch {
                clipboard.writePlainText(source)
                copied = true
            }
        },
        modifier = Modifier.testTag(CodeFenceTags.COPY).semantics { liveRegion = LiveRegionMode.Polite },
    )
}
