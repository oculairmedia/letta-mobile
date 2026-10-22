package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import com.letta.mobile.data.chat.tools.ToolArgumentField
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolOutputStream
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_copy_command
import com.letta.mobile.sharedui.resources.rows_copy_output
import com.letta.mobile.sharedui.resources.rows_tool_output_truncated
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * What an opened tool step shows, for every [ToolInset] a presenter can ask for: a soft surface
 * panel with no border, its text wrapped (never scrolled sideways) and its height bounded, past
 * which it scrolls. A command reads `dir $ command` then its output; stderr and removed text are
 * a muted red. Long outputs keep only their last lines ([OutputWindow]).
 */
@Composable
internal fun ToolInsetView(inset: ToolInset, modifier: Modifier = Modifier) {
    when (inset) {
        is ToolInset.Diff -> BoundedScroll(modifier) { DiffBlock(inset.unified) }
        is ToolInset.Command -> InsetPanel(modifier) { CommandInset(inset) }
        is ToolInset.Text -> InsetPanel(modifier) { OutputText(inset.text, InsetInk.Plain) }
        is ToolInset.BeforeAfter -> InsetPanel(modifier) { BeforeAfterInset(inset) }
        is ToolInset.Arguments -> InsetPanel(modifier) { ArgumentsInset(inset) }
    }
}

/** The inset's surface: surface-1, no border, bounded then scrolling. */
@Composable
private fun InsetPanel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(ChatRowTestTags.TOOL_OUTPUT),
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        BoundedScroll(Modifier) {
            SelectionContainer {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(LettaDimens.Space.md),
                    verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun BoundedScroll(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = ChatRowDimens.toolInsetMaxHeight)
            .testTag(ChatRowTestTags.TOOL_INSET_VIEWPORT)
            .verticalScroll(rememberScrollState()),
    ) { content() }
}

@Composable
private fun CommandInset(inset: ToolInset.Command) {
    if (inset.command.isNotBlank()) {
        val prompt = inset.workingDirectory?.let { "$it $PROMPT" } ?: PROMPT
        HoverCopy(inset.command, stringResource(Res.string.rows_copy_command)) {
            CommandLine(prompt, inset.command)
        }
    }
    inset.output.forEach { stream ->
        val ink = if (stream.kind == ToolOutputStream.Kind.Stderr) InsetInk.Removed else InsetInk.Plain
        HoverCopy(stream.text, stringResource(Res.string.rows_copy_output)) { OutputText(stream.text, ink) }
    }
}

/** `dir $ ` muted, then the command in full. */
@Composable
private fun CommandLine(prompt: String, command: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = remember(prompt, command, muted) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = muted)) { append(prompt) }
            append(command)
        }
    }
    Text(
        text = text,
        style = monoStyle(),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(ChatRowTestTags.TOOL_COMMAND),
    )
}

@Composable
private fun BeforeAfterInset(inset: ToolInset.BeforeAfter) {
    if (inset.before.isNotEmpty()) OutputText(inset.before, InsetInk.Removed, marker = REMOVED_MARKER)
    if (inset.after.isNotEmpty()) OutputText(inset.after, InsetInk.Added, marker = ADDED_MARKER)
}

@Composable
private fun ArgumentsInset(inset: ToolInset.Arguments) {
    inset.fields.forEach { ArgumentLine(it) }
    inset.result?.let { result -> HoverCopy(result, stringResource(Res.string.rows_copy_output)) { OutputText(result, InsetInk.Plain) } }
}

@Composable
private fun ArgumentLine(field: ToolArgumentField) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = remember(field, muted) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = muted)) {
                append(field.key)
                append(": ")
            }
            append(field.value)
        }
    }
    Text(text = text, style = monoStyle(), color = MaterialTheme.colorScheme.onSurface)
}

/** [text]'s last lines, wrapped, in [ink]; says how many lines it left out above them. */
@Composable
private fun OutputText(text: String, ink: InsetInk, marker: String? = null) {
    val window = remember(text) { OutputWindow.tail(text) }
    if (window.hiddenLines > 0) {
        Text(
            text = stringResource(Res.string.rows_tool_output_truncated, window.visibleLines, window.visibleLines + window.hiddenLines),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ChatRowTestTags.TOOL_OUTPUT_TRUNCATED),
        )
    }
    val shown = if (marker == null) window.text else window.text.lines().joinToString("\n") { "$marker$it" }
    Text(text = shown, style = monoStyle(), color = ink.color())
}

/** [content] with a copy action for [text] that shows while the pointer is over it. */
@Composable
private fun HoverCopy(text: String, label: String, content: @Composable () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(Modifier.fillMaxWidth().hoverable(hover)) {
        content()
        CopyIconButton(
            CopyAction(text = text, contentDescription = label, visible = hovered),
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun monoStyle(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

/** The inks an inset's text reads in: muted output, muted red for stderr and removals, muted green for additions. */
private enum class InsetInk {
    Plain,
    Removed,
    Added,
    ;

    @Composable
    fun color(): Color = when (this) {
        Plain -> MaterialTheme.colorScheme.onSurfaceVariant
        Removed -> MaterialTheme.colorScheme.error.copy(alpha = ChatRowAlpha.insetMutedInk)
        Added -> successTint().copy(alpha = ChatRowAlpha.insetMutedInk)
    }
}

/**
 * The end of an output an inset draws: at most [ChatRowDimens.toolOutputVisibleLines] lines from
 * its end (trailing blank space dropped), and how many lines came before them. Only the kept
 * lines are copied out of the output; the rest is counted, never split into lines.
 */
internal class OutputWindow private constructor(val text: String, val visibleLines: Int, val hiddenLines: Int) {
    companion object {
        fun tail(raw: String, maxLines: Int = ChatRowDimens.toolOutputVisibleLines): OutputWindow {
            var end = raw.length
            while (end > 0 && raw[end - 1].isWhitespace()) end--
            var start = end
            var kept = 1
            while (start > 0) {
                if (raw[start - 1] == '\n') {
                    if (kept == maxLines) break
                    kept++
                }
                start--
            }
            val hidden = if (start == 0) 0 else raw.count(0, start) { it == '\n' }
            return OutputWindow(raw.substring(start, end), if (end == 0) 0 else kept, hidden)
        }

        private inline fun String.count(from: Int, until: Int, predicate: (Char) -> Boolean): Int {
            var count = 0
            for (index in from until until) if (predicate(this[index])) count++
            return count
        }
    }
}

private const val REMOVED_MARKER = "- "
private const val ADDED_MARKER = "+ "

/** A shell prompt's sigil, not words: the same in every language. */
private const val PROMPT = "$ "
