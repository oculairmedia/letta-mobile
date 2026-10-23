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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.letta.mobile.data.chat.tools.ToolArgumentField
import com.letta.mobile.data.chat.tools.ToolInset
import com.letta.mobile.data.chat.tools.ToolOutputStream
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_copy_command
import com.letta.mobile.sharedui.resources.rows_copy_output
import com.letta.mobile.sharedui.resources.rows_tool_output_truncated
import com.letta.mobile.sharedui.resources.rows_tool_replaced_whole_block
import com.letta.mobile.ui.shell.pages.workspace.rememberWorkspaceLinkedText
import com.letta.mobile.ui.theme.ChatRowAlpha
import com.letta.mobile.ui.theme.ChatRowDimens
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What an opened tool step shows, for every [ToolInset] a presenter can ask for: a soft surface
 * panel with no border, its text wrapped (never scrolled sideways) and its height bounded, past
 * which it scrolls. A command reads `dir $ command` then its output; stderr and removed text are
 * a muted red. Long outputs keep only their last lines ([OutputWindow]).
 */
@Composable
internal fun ToolInsetView(inset: ToolInset, modifier: Modifier = Modifier, live: Boolean = false) {
    when (inset) {
        is ToolInset.Diff -> BoundedScroll(modifier, live) { DiffBlock(inset.unified) }
        is ToolInset.Command -> InsetPanel(modifier, live) { CommandInset(inset) }
        is ToolInset.Text -> InsetPanel(modifier, live) { OutputText(inset.text, InsetInk.Plain) }
        is ToolInset.BeforeAfter -> InsetPanel(modifier, live) { BeforeAfterInset(inset) }
        is ToolInset.Rewrite -> InsetPanel(modifier, live) { RewriteInset(inset) }
        is ToolInset.Arguments -> InsetPanel(modifier, live) { ArgumentsInset(inset) }
        is ToolInset.Checklist -> InsetPanel(modifier, live) { ChecklistInset(inset) }
    }
}

/** The inset's surface: surface-1, no border, bounded then scrolling. */
@Composable
private fun InsetPanel(modifier: Modifier, live: Boolean, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag(ChatRowTestTags.TOOL_OUTPUT),
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        BoundedScroll(Modifier, live) {
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
private fun BoundedScroll(modifier: Modifier, live: Boolean, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(max = ChatRowDimens.toolInsetMaxHeight)
            .testTag(ChatRowTestTags.TOOL_INSET_VIEWPORT)
            .verticalScroll(rememberTailFollowingScroll(live)),
    ) { content() }
}

@Composable
private fun CommandInset(inset: ToolInset.Command) {
    if (inset.command.isNotBlank()) {
        HoverCopy(inset.command, Res.string.rows_copy_command) { CommandLine(inset) }
    }
    inset.output.forEach { stream ->
        val ink = if (stream.kind == ToolOutputStream.Kind.Stderr) InsetInk.Stderr else InsetInk.Plain
        HoverCopy(stream.text, Res.string.rows_copy_output) { OutputText(stream.text, ink) }
    }
}

/** `dir $ ` muted, then the command in full. */
@Composable
private fun CommandLine(inset: ToolInset.Command) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = remember(inset, muted) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = muted)) {
                inset.workingDirectory?.let {
                    append(it)
                    append(" ")
                }
                append(PROMPT)
            }
            append(inset.command)
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
    if (inset.before.isNotEmpty()) OutputText(inset.before, InsetInk.Removed)
    if (inset.after.isNotEmpty()) OutputText(inset.after, InsetInk.Added)
}

/** The new value, then a muted note that it replaced all of what was there. */
@Composable
private fun RewriteInset(inset: ToolInset.Rewrite) {
    OutputText(inset.value, InsetInk.Plain)
    Text(
        text = stringResource(Res.string.rows_tool_replaced_whole_block),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ArgumentsInset(inset: ToolInset.Arguments) {
    inset.fields.forEach { ArgumentLine(it) }
    inset.result?.let { result -> HoverCopy(result, Res.string.rows_copy_output) { OutputText(result, InsetInk.Plain) } }
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

/**
 * [text]'s last lines, wrapped, in [ink], their URLs and paths as links. Above them it says how
 * many lines it left out, with "Open full output", which shows all of them in a detail pane.
 */
@Composable
private fun OutputText(text: String, ink: InsetInk) {
    val window = remember(text) { OutputWindow.tail(text) }
    if (window.hiddenLines > 0) {
        var full by remember { mutableStateOf(false) }
        Text(
            text = stringResource(Res.string.rows_tool_output_truncated, window.visibleLines, window.visibleLines + window.hiddenLines),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(ChatRowTestTags.TOOL_OUTPUT_TRUNCATED),
        )
        FullOutputLink { full = true }
        if (full) FullOutputPane(text) { full = false }
    }
    val shown = remember(window, ink) { ink.marked(window.text) }
    Text(text = rememberWorkspaceLinkedText(shown), style = monoStyle().copy(textDecoration = ink.decoration), color = ink.color())
}

/** [content] with a copy action for [text], named [label], that shows while the pointer is over it. */
@Composable
private fun HoverCopy(text: String, label: StringResource, content: @Composable () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(Modifier.fillMaxWidth().hoverable(hover)) {
        content()
        CopyIconButton(
            CopyAction(text = text, contentDescription = stringResource(label), visible = hovered),
            modifier = Modifier.align(Alignment.TopEnd),
        )
    }
}

@Composable
private fun monoStyle(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

/**
 * The inks an inset's text reads in: muted output, muted red for stderr, and the before / after of
 * a replacement in muted red / green, each line led by its marker.
 */
private enum class InsetInk(private val marker: String?) {
    Plain(null),
    Stderr(null),
    Removed("- "),
    Added("+ "),
    ;

    /** Removed text is struck through, as in every diff. */
    val decoration: TextDecoration? get() = RemovedTextDecoration.takeIf { this == Removed }

    fun marked(text: String): String = marker?.let { prefix -> text.lines().joinToString("\n") { prefix + it } } ?: text

    @Composable
    fun color(): Color = when (this) {
        Plain -> MaterialTheme.colorScheme.onSurfaceVariant
        Stderr, Removed -> MaterialTheme.colorScheme.error.copy(alpha = ChatRowAlpha.insetMutedInk)
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
            val end = raw.indexOfLast { !it.isWhitespace() } + 1
            // The newlines before the last lines, nearest first; the one past maxLines - 1 cuts the window.
            val breaks = generateSequence(raw.lastIndexOf('\n', end - 1)) { raw.lastIndexOf('\n', it - 1) }.takeWhile { it >= 0 }
            val start = breaks.drop(maxLines - 1).firstOrNull()?.plus(1) ?: 0
            val hidden = (0 until start).count { raw[it] == '\n' }
            val kept = if (end == 0) 0 else (start until end).count { raw[it] == '\n' } + 1
            return OutputWindow(raw.substring(start, end), kept, hidden)
        }
    }
}

/** How removed text reads in every diff and replacement: struck through, besides its muted red. */
internal val RemovedTextDecoration: TextDecoration = TextDecoration.LineThrough

/** A shell prompt's sigil, not words: the same in every language. */
private const val PROMPT = "$ "
