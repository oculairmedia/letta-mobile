package com.letta.mobile.ui.screens.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.data.tooloutput.DiffFile
import com.letta.mobile.data.tooloutput.DiffLine
import com.letta.mobile.data.tooloutput.DiffLineType
import com.letta.mobile.data.tooloutput.ToolOutputBlock
import com.letta.mobile.data.tooloutput.ToolOutputDocument
import com.letta.mobile.data.tooloutput.ToolOutputParser
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.chatTypography
import com.letta.mobile.ui.theme.customColors
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.theme.scaledBy
import java.util.LinkedHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val ToolOutputBackgroundParseThresholdChars = 12_000
internal const val ToolOutputBackgroundHighlightThresholdChars = 4_000
internal const val ToolOutputMaxRenderedChars = 20_000
internal const val ToolOutputMaxRenderedLines = 320
internal const val ToolOutputPreviewMaxRenderedChars = 1_200
internal const val ToolOutputMaxHighlightSpans = 800
internal const val ToolOutputDocumentMaxCacheableRawChars = ToolOutputParser.MaxAnalyzedChars

private const val TOOL_OUTPUT_DOCUMENT_CACHE_ENTRIES = 32
private const val TOOL_OUTPUT_HIGHLIGHT_CACHE_ENTRIES = 128
private const val TOOL_OUTPUT_CACHE_FINGERPRINT_CHARS = 96

private val toolOutputDocumentCache =
    ToolOutputLruCache<ToolOutputContentKey, ToolOutputDocument>(
        TOOL_OUTPUT_DOCUMENT_CACHE_ENTRIES,
    )
private val toolOutputHighlightCache =
    ToolOutputLruCache<ToolOutputHighlightCacheKey, List<ToolOutputHighlightSpan>>(
        TOOL_OUTPUT_HIGHLIGHT_CACHE_ENTRIES,
    )

@Composable
@OptIn(ExperimentalAnimationApi::class)
internal fun ToolOutputRenderer(
    raw: String,
    expanded: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val rawCacheKey = remember(raw) { raw.toolOutputContentKey() }
    val cachedDocument = remember(rawCacheKey) { toolOutputDocumentCache.get(rawCacheKey) }
    val initialDocument = remember(rawCacheKey, raw, cachedDocument) {
        cachedDocument ?: initialToolOutputDocument(raw)
    }
    val document by produceState(initialValue = initialDocument, rawCacheKey, raw, cachedDocument) {
        if (cachedDocument == null && raw.length > ToolOutputBackgroundParseThresholdChars) {
            value = withContext(Dispatchers.Default) { cachedToolOutputDocument(raw) }
        }
    }
    // letta-mobile-3wjn: Restore eased expand/collapse on user-initiated taps.
    // Commit 74314380 stripped this when stabilizing the run timeline. The
    // animation here is keyed on `expanded` (a user-toggled state), not on
    // streamed `raw` content growth, so it does not collide with the
    // LazyColumn measurement issue that 74314380 fixed. We still suppress
    // during pinch because animateContentSize/AnimatedContent during a
    // multi-touch gesture can cascade height interpolations across bubbles.
    val isPinching = LocalChatIsPinching.current
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier
            .pointerInput(raw) {
                detectTapGestures(
                    onLongPress = { clipboard.setText(AnnotatedString(raw)) },
                )
            },
    ) {
        if (isPinching) {
            ToolOutputBody(
                document = document,
                expanded = expanded,
                isError = isError,
            )
        } else {
            AnimatedContent(
                targetState = expanded,
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = {
                    (ChatMotion.expandEnter() togetherWith ChatMotion.expandExit())
                        .using(SizeTransform(clip = true) { _, _ -> ChatMotion.contentSizeSpec })
                },
                contentAlignment = Alignment.TopStart,
                label = "ToolOutputExpandedState",
            ) { isExpanded ->
                ToolOutputBody(
                    document = document,
                    expanded = isExpanded,
                    isError = isError,
                )
            }
        }
    }
}

@Composable
private fun ToolOutputBody(
    document: ToolOutputDocument,
    expanded: Boolean,
    isError: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (expanded) {
            document.blocks.forEach { block ->
                ToolOutputBlockView(block = block, isError = isError)
            }
        } else {
            ToolOutputPreview(document = document, isError = isError)
        }
        if (expanded && document.isTruncated) {
            ToolOutputLimitNotice(
                text = stringResource(
                    R.string.screen_chat_tool_output_truncated,
                    document.omittedCharCount,
                ),
            )
        }
    }
}

internal fun initialToolOutputDocument(raw: String): ToolOutputDocument {
    if (raw.length <= ToolOutputBackgroundParseThresholdChars) {
        return cachedToolOutputDocument(raw)
    }
    val preview = ToolOutputParser.stripAnsi(raw.take(ToolOutputMaxRenderedChars))
    return ToolOutputDocument(
        raw = raw,
        blocks = listOf(ToolOutputBlock.PlainText(preview)),
        isTruncated = raw.length > preview.length,
        omittedCharCount = raw.length - preview.length,
    )
}

@Composable
private fun ToolOutputPreview(
    document: ToolOutputDocument,
    isError: Boolean,
) {
    val block = remember(document) { document.blocks.firstOrNull() }
    val preview = remember(block) {
        block?.previewText().orEmpty()
    }
    CodeOutputSurface(
        text = preview,
        isError = isError,
        maxLines = 2,
        maxRenderedLines = 2,
        maxRenderedChars = ToolOutputPreviewMaxRenderedChars,
        showLimitNotice = false,
        highlightMode = block?.highlightMode() ?: ToolOutputHighlightMode.None,
        languageHint = (block as? ToolOutputBlock.CodeLike)?.languageHint,
    )
}

@Composable
private fun ToolOutputBlockView(
    block: ToolOutputBlock,
    isError: Boolean,
) {
    when (block) {
        is ToolOutputBlock.Json -> CodeOutputSurface(
            text = block.pretty,
            highlightMode = ToolOutputHighlightMode.Json,
        )

        is ToolOutputBlock.Diff -> DiffOutputSurface(block)

        is ToolOutputBlock.StackTrace -> StackTraceOutputSurface(block)

        is ToolOutputBlock.ShellTranscript -> ShellTranscriptSurface(block, isError = isError)

        is ToolOutputBlock.AnsiLog -> CodeOutputSurface(
            text = block.stripped,
            isError = isError,
            highlightMode = ToolOutputHighlightMode.Log,
        )

        is ToolOutputBlock.Table -> CodeOutputSurface(
            text = remember(block.rows) { formatTable(block.rows) },
            highlightMode = ToolOutputHighlightMode.Table,
        )

        is ToolOutputBlock.CodeLike -> CodeOutputSurface(
            text = block.raw,
            highlightMode = ToolOutputHighlightMode.Code,
            languageHint = block.languageHint,
        )

        is ToolOutputBlock.PlainText -> CodeOutputSurface(
            text = block.raw,
            isError = isError,
            highlightMode = ToolOutputHighlightMode.Log,
        )
    }
}

@Composable
private fun CodeOutputSurface(
    text: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    maxRenderedLines: Int = ToolOutputMaxRenderedLines,
    maxRenderedChars: Int = ToolOutputMaxRenderedChars,
    showLimitNotice: Boolean = true,
    highlightMode: ToolOutputHighlightMode = ToolOutputHighlightMode.None,
    languageHint: String? = null,
) {
    val limited = remember(text, maxRenderedLines, maxRenderedChars) {
        limitRenderedText(text, maxLines = maxRenderedLines, maxChars = maxRenderedChars)
    }
    val syntaxColors = toolOutputSyntaxColors(isError)
    val highlightInBackground = limited.text.length > ToolOutputBackgroundHighlightThresholdChars &&
        highlightMode != ToolOutputHighlightMode.None
    val highlightCacheKey = remember(limited.text, highlightMode, languageHint) {
        ToolOutputHighlightCacheKey(
            content = limited.text.toolOutputContentKey(),
            mode = highlightMode,
            languageHint = languageHint,
        )
    }
    val cachedSpans = remember(highlightCacheKey) { toolOutputHighlightCache.get(highlightCacheKey) }
    val initialAnnotatedText = remember(
        limited.text,
        highlightMode,
        languageHint,
        syntaxColors,
        highlightInBackground,
        cachedSpans,
    ) {
        when {
            highlightMode == ToolOutputHighlightMode.None -> AnnotatedString(limited.text)
            cachedSpans != null -> annotatedToolOutputText(
                text = limited.text,
                spans = cachedSpans,
                colors = syntaxColors,
            )
            highlightInBackground -> AnnotatedString(limited.text)
            else -> annotatedToolOutputText(
                text = limited.text,
                spans = cachedToolOutputHighlightSpans(
                    text = limited.text,
                    mode = highlightMode,
                    languageHint = languageHint,
                ),
                colors = syntaxColors,
            )
        }
    }
    val annotatedText by produceState(
        initialValue = initialAnnotatedText,
        limited.text,
        highlightMode,
        languageHint,
        syntaxColors,
        highlightInBackground,
        cachedSpans,
    ) {
        if (highlightInBackground && cachedSpans == null) {
            val spans = withContext(Dispatchers.Default) {
                cachedToolOutputHighlightSpans(
                    text = limited.text,
                    mode = highlightMode,
                    languageHint = languageHint,
                )
            }
            value = annotatedToolOutputText(
                text = limited.text,
                spans = spans,
                colors = syntaxColors,
            )
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            MonospaceText(
                text = annotatedText,
                color = syntaxColors.default,
                maxLines = maxLines,
            )
            if (showLimitNotice) {
                when {
                    limited.omittedLines > 0 -> ToolOutputLimitNotice(
                        text = stringResource(R.string.screen_chat_tool_output_lines_omitted, limited.omittedLines),
                    )
                    limited.omittedChars > 0 -> ToolOutputLimitNotice(
                        text = stringResource(R.string.screen_chat_tool_output_chars_omitted, limited.omittedChars),
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffOutputSurface(block: ToolOutputBlock.Diff) {
    val limited = remember(block.files) { limitDiffFilesForRendering(block.files) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                limited.files.forEach { file ->
                    Text(
                        text = file.newPath ?: file.oldPath ?: stringResource(R.string.screen_chat_tool_output_diff_file),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
                            .scaledBy(LocalChatFontScale.current),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.86f),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    file.lines.forEach { line ->
                        DiffLineText(line)
                    }
                }
            }
            if (limited.omittedLines > 0) {
                ToolOutputLimitNotice(
                    text = stringResource(R.string.screen_chat_tool_output_lines_omitted, limited.omittedLines),
                )
            }
        }
    }
}

@Composable
private fun DiffLineText(line: DiffLine) {
    val color = when (line.type) {
        DiffLineType.Added -> MaterialTheme.colorScheme.primary
        DiffLineType.Removed -> MaterialTheme.colorScheme.error
        DiffLineType.Hunk -> MaterialTheme.colorScheme.tertiary
        DiffLineType.Header -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
        DiffLineType.Context -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    MonospaceText(text = line.text, color = color)
}

@Composable
private fun StackTraceOutputSurface(block: ToolOutputBlock.StackTrace) {
    val syntaxColors = toolOutputSyntaxColors(isError = false)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            MonospaceText(text = block.headline, color = MaterialTheme.colorScheme.error)
            block.frames.take(ToolOutputMaxRenderedLines).forEach { frame ->
                val location = buildString {
                    frame.file?.let { append(it) }
                    frame.line?.let { append(":").append(it) }
                }
                val frameText = if (location.isBlank()) frame.text else "${frame.symbol.orEmpty()}  $location"
                MonospaceText(
                    text = highlightedToolOutputText(
                        text = frameText,
                        mode = ToolOutputHighlightMode.Code,
                        languageHint = block.languageHint,
                        colors = syntaxColors,
                    ),
                    color = syntaxColors.default,
                )
            }
            if (block.frames.size > ToolOutputMaxRenderedLines) {
                ToolOutputLimitNotice(
                    text = stringResource(
                        R.string.screen_chat_tool_output_lines_omitted,
                        block.frames.size - ToolOutputMaxRenderedLines,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ShellTranscriptSurface(
    block: ToolOutputBlock.ShellTranscript,
    isError: Boolean,
) {
    CodeOutputSurface(
        text = buildString {
            block.commandLines.forEach { command ->
                append(command)
                append('\n')
            }
            if (block.output.isNotBlank()) {
                append(block.output)
            }
        }.trimEnd(),
        isError = isError,
        highlightMode = ToolOutputHighlightMode.Shell,
    )
}

@Composable
private fun MonospaceText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    MonospaceText(
        text = AnnotatedString(text),
        color = color,
        modifier = modifier,
        maxLines = maxLines,
    )
}

@Composable
private fun MonospaceText(
    text: AnnotatedString,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.listItemSupporting
            .copy(fontFamily = MaterialTheme.chatTypography.codeBlock.fontFamily ?: FontFamily.Monospace)
            .scaledBy(LocalChatFontScale.current),
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        softWrap = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun ToolOutputLimitNotice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.scaledBy(LocalChatFontScale.current),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
    )
}

private fun ToolOutputBlock.previewText(): String = when (this) {
    is ToolOutputBlock.Json -> pretty
    is ToolOutputBlock.Diff -> raw
    is ToolOutputBlock.StackTrace -> headline
    is ToolOutputBlock.ShellTranscript -> if (output.isNotBlank()) output else commandLines.joinToString("\n")
    is ToolOutputBlock.AnsiLog -> stripped
    is ToolOutputBlock.Table -> formatTable(rows)
    is ToolOutputBlock.CodeLike -> raw
    is ToolOutputBlock.PlainText -> raw
}

private fun ToolOutputBlock.highlightMode(): ToolOutputHighlightMode = when (this) {
    is ToolOutputBlock.Json -> ToolOutputHighlightMode.Json
    is ToolOutputBlock.Diff -> ToolOutputHighlightMode.None
    is ToolOutputBlock.StackTrace -> ToolOutputHighlightMode.Log
    is ToolOutputBlock.ShellTranscript -> ToolOutputHighlightMode.Shell
    is ToolOutputBlock.AnsiLog -> ToolOutputHighlightMode.Log
    is ToolOutputBlock.Table -> ToolOutputHighlightMode.Table
    is ToolOutputBlock.CodeLike -> ToolOutputHighlightMode.Code
    is ToolOutputBlock.PlainText -> ToolOutputHighlightMode.Log
}

internal enum class ToolOutputHighlightMode {
    None,
    Json,
    Code,
    Shell,
    Log,
    Table,
}

internal enum class ToolOutputHighlightKind {
    Key,
    StringLiteral,
    Number,
    Literal,
    Keyword,
    Function,
    Comment,
    Punctuation,
    Prompt,
    Success,
    Warning,
    Error,
    Header,
}

internal data class ToolOutputHighlightSpan(
    val start: Int,
    val end: Int,
    val kind: ToolOutputHighlightKind,
)

private data class ToolOutputContentKey(
    val length: Int,
    val hash: Int,
    val prefix: String,
    val suffix: String,
)

private data class ToolOutputHighlightCacheKey(
    val content: ToolOutputContentKey,
    val mode: ToolOutputHighlightMode,
    val languageHint: String?,
)

private fun String.toolOutputContentKey(): ToolOutputContentKey =
    ToolOutputContentKey(
        length = length,
        hash = hashCode(),
        prefix = take(TOOL_OUTPUT_CACHE_FINGERPRINT_CHARS),
        suffix = takeLast(TOOL_OUTPUT_CACHE_FINGERPRINT_CHARS),
    )

internal fun cachedToolOutputDocument(raw: String): ToolOutputDocument {
    if (raw.length > ToolOutputDocumentMaxCacheableRawChars) {
        return ToolOutputParser.parse(raw)
    }
    return toolOutputDocumentCache.getOrPut(raw.toolOutputContentKey()) {
        ToolOutputParser.parse(raw)
    }
}

internal fun cachedToolOutputHighlightSpans(
    text: String,
    mode: ToolOutputHighlightMode,
    languageHint: String? = null,
): List<ToolOutputHighlightSpan> =
    toolOutputHighlightCache.getOrPut(
        ToolOutputHighlightCacheKey(
            content = text.toolOutputContentKey(),
            mode = mode,
            languageHint = languageHint,
        )
    ) {
        highlightToolOutputText(text = text, mode = mode, languageHint = languageHint)
    }

internal fun clearToolOutputRenderCachesForTest() {
    toolOutputDocumentCache.clear()
    toolOutputHighlightCache.clear()
}

private class ToolOutputLruCache<K, V>(
    private val maxEntries: Int,
) {
    private val lock = Any()
    private val values = object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
            size > maxEntries
    }

    fun get(key: K): V? = synchronized(lock) {
        values[key]
    }

    fun getOrPut(key: K, producer: () -> V): V {
        get(key)?.let { return it }
        val produced = producer()
        return synchronized(lock) {
            values[key] ?: produced.also { values[key] = it }
        }
    }

    fun clear() {
        synchronized(lock) {
            values.clear()
        }
    }
}

private data class ToolOutputSyntaxColors(
    val default: Color,
    val key: Color,
    val stringLiteral: Color,
    val number: Color,
    val literal: Color,
    val keyword: Color,
    val function: Color,
    val comment: Color,
    val punctuation: Color,
    val prompt: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val header: Color,
)

@Composable
private fun toolOutputSyntaxColors(isError: Boolean): ToolOutputSyntaxColors {
    val customColors = MaterialTheme.customColors
    val scheme = MaterialTheme.colorScheme
    return ToolOutputSyntaxColors(
        default = if (isError) scheme.error else scheme.onSurfaceVariant,
        key = scheme.primary,
        stringLiteral = scheme.tertiary,
        number = scheme.secondary,
        literal = scheme.primary.copy(alpha = 0.88f),
        keyword = scheme.primary,
        function = scheme.secondary,
        comment = scheme.onSurfaceVariant.copy(alpha = 0.58f),
        punctuation = scheme.onSurfaceVariant.copy(alpha = 0.62f),
        prompt = scheme.primary,
        success = customColors.successColor,
        warning = customColors.warningTextColor,
        error = scheme.error,
        header = scheme.primary.copy(alpha = 0.88f),
    )
}

private fun highlightedToolOutputText(
    text: String,
    mode: ToolOutputHighlightMode,
    languageHint: String?,
    colors: ToolOutputSyntaxColors,
): AnnotatedString {
    val spans = highlightToolOutputText(text = text, mode = mode, languageHint = languageHint)
    return annotatedToolOutputText(text = text, spans = spans, colors = colors)
}

private fun annotatedToolOutputText(
    text: String,
    spans: List<ToolOutputHighlightSpan>,
    colors: ToolOutputSyntaxColors,
): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(text)

    val builder = AnnotatedString.Builder()
    builder.append(text)
    spans.sortedWith(compareBy<ToolOutputHighlightSpan> { it.start }.thenBy { it.end }).forEach { span ->
        if (span.start in 0 until span.end && span.end <= text.length) {
            builder.addStyle(SpanStyle(color = colors.colorFor(span.kind)), span.start, span.end)
        }
    }
    return builder.toAnnotatedString()
}

private fun ToolOutputSyntaxColors.colorFor(kind: ToolOutputHighlightKind): Color = when (kind) {
    ToolOutputHighlightKind.Key -> key
    ToolOutputHighlightKind.StringLiteral -> stringLiteral
    ToolOutputHighlightKind.Number -> number
    ToolOutputHighlightKind.Literal -> literal
    ToolOutputHighlightKind.Keyword -> keyword
    ToolOutputHighlightKind.Function -> function
    ToolOutputHighlightKind.Comment -> comment
    ToolOutputHighlightKind.Punctuation -> punctuation
    ToolOutputHighlightKind.Prompt -> prompt
    ToolOutputHighlightKind.Success -> success
    ToolOutputHighlightKind.Warning -> warning
    ToolOutputHighlightKind.Error -> error
    ToolOutputHighlightKind.Header -> header
}

internal fun highlightToolOutputText(
    text: String,
    mode: ToolOutputHighlightMode,
    languageHint: String? = null,
): List<ToolOutputHighlightSpan> = when (mode) {
    ToolOutputHighlightMode.None -> emptyList()
    ToolOutputHighlightMode.Json -> highlightJsonOutput(text)
    ToolOutputHighlightMode.Code -> highlightCodeOutput(text, languageHint)
    ToolOutputHighlightMode.Shell -> highlightShellOutput(text)
    ToolOutputHighlightMode.Log -> highlightLogOutput(text)
    ToolOutputHighlightMode.Table -> highlightTableOutput(text)
}

private fun highlightJsonOutput(text: String): List<ToolOutputHighlightSpan> {
    val spans = mutableListOf<ToolOutputHighlightSpan>()
    var index = 0
    while (index < text.length && spans.hasHighlightBudget()) {
        when {
            text[index] == '"' -> {
                val end = scanQuotedString(text, index)
                val kind = if (isJsonObjectKey(text, end)) {
                    ToolOutputHighlightKind.Key
                } else {
                    ToolOutputHighlightKind.StringLiteral
                }
                spans.addValid(index, end, kind)
                index = end
            }
            text[index].isNumberStart(text, index) -> {
                val end = scanNumber(text, index)
                spans.addValid(index, end, ToolOutputHighlightKind.Number)
                index = end
            }
            text.startsWithJsonLiteral(index) != null -> {
                val literal = text.startsWithJsonLiteral(index).orEmpty()
                spans.addValid(index, index + literal.length, ToolOutputHighlightKind.Literal)
                index += literal.length
            }
            text[index] in "{}[]:," -> {
                spans.addValid(index, index + 1, ToolOutputHighlightKind.Punctuation)
                index++
            }
            else -> index++
        }
    }
    return spans
}

private fun highlightCodeOutput(text: String, languageHint: String?): List<ToolOutputHighlightSpan> {
    val spans = mutableListOf<ToolOutputHighlightSpan>()
    val occupied = BooleanArray(text.length)

    fun addTracked(start: Int, end: Int, kind: ToolOutputHighlightKind) {
        if (spans.addValid(start, end, kind)) {
            occupied.mark(start, end)
        }
    }

    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("//", index) -> {
                val end = text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length
                addTracked(index, end, ToolOutputHighlightKind.Comment)
                index = end
            }
            text.startsWith("/*", index) -> {
                val end = text.indexOf("*/", index + 2).takeIf { it >= 0 }?.plus(2) ?: text.length
                addTracked(index, end, ToolOutputHighlightKind.Comment)
                index = end
            }
            text[index] == '#' && languageHint.allowsHashComments() -> {
                val end = text.indexOf('\n', index).takeIf { it >= 0 } ?: text.length
                addTracked(index, end, ToolOutputHighlightKind.Comment)
                index = end
            }
            text[index] == '"' || text[index] == '\'' || text[index] == '`' -> {
                val end = scanQuotedString(text, index)
                addTracked(index, end, ToolOutputHighlightKind.StringLiteral)
                index = end
            }
            else -> index++
        }
    }

    for (match in keywordRegex(languageHint).findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        addIfClear(spans, occupied, match.range.first, match.range.last + 1, ToolOutputHighlightKind.Keyword)
    }
    for (match in codeNumberRegex.findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        addIfClear(spans, occupied, match.range.first, match.range.last + 1, ToolOutputHighlightKind.Number)
    }
    for (match in codeFunctionRegex.findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        addIfClear(spans, occupied, match.range.first, match.range.last + 1, ToolOutputHighlightKind.Function)
    }
    for (position in text.indices) {
        if (!spans.hasHighlightBudget()) break
        val char = text[position]
        if (char in codePunctuation && !occupied[position]) {
            spans.addValid(position, position + 1, ToolOutputHighlightKind.Punctuation)
        }
    }
    return spans
}

private fun highlightShellOutput(text: String): List<ToolOutputHighlightSpan> {
    val spans = highlightLogOutput(text).toMutableList()
    var lineStart = 0
    for (line in text.split('\n')) {
        if (!spans.hasHighlightBudget()) break
        val promptLength = when {
            line.startsWith("$ ") -> 2
            line.startsWith("> ") -> 2
            line.startsWith("PS> ") -> 4
            else -> 0
        }
        if (promptLength > 0) {
            spans.addValid(lineStart, lineStart + promptLength, ToolOutputHighlightKind.Prompt)
            val commandStart = lineStart + promptLength
            val commandEnd = findShellCommandEnd(text, commandStart, lineStart + line.length)
            spans.addValid(commandStart, commandEnd, ToolOutputHighlightKind.Keyword)
            for (match in shellFlagRegex.findAll(line)) {
                if (!spans.hasHighlightBudget()) break
                val start = lineStart + match.range.first
                val end = lineStart + match.range.last + 1
                if (start >= commandEnd) {
                    spans.addValid(start, end, ToolOutputHighlightKind.Literal)
                }
            }
        }
        lineStart += line.length + 1
    }
    return spans
}

private fun highlightLogOutput(text: String): List<ToolOutputHighlightSpan> {
    val spans = mutableListOf<ToolOutputHighlightSpan>()
    for (match in errorWordRegex.findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        spans.addValid(match.range.first, match.range.last + 1, ToolOutputHighlightKind.Error)
    }
    for (match in warningWordRegex.findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        spans.addValid(match.range.first, match.range.last + 1, ToolOutputHighlightKind.Warning)
    }
    for (match in successWordRegex.findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        spans.addValid(match.range.first, match.range.last + 1, ToolOutputHighlightKind.Success)
    }
    for (match in codeNumberRegex.findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        spans.addValid(match.range.first, match.range.last + 1, ToolOutputHighlightKind.Number)
    }
    return spans
}

private fun highlightTableOutput(text: String): List<ToolOutputHighlightSpan> {
    val spans = mutableListOf<ToolOutputHighlightSpan>()
    val headerEnd = text.indexOf('\n').takeIf { it >= 0 } ?: text.length
    spans.addValid(0, headerEnd, ToolOutputHighlightKind.Header)
    for (match in codeNumberRegex.findAll(text)) {
        if (!spans.hasHighlightBudget()) break
        spans.addValid(match.range.first, match.range.last + 1, ToolOutputHighlightKind.Number)
    }
    return spans
}

private fun MutableList<ToolOutputHighlightSpan>.addValid(
    start: Int,
    end: Int,
    kind: ToolOutputHighlightKind,
): Boolean {
    if (start < 0 || end <= start) return false
    if (!hasHighlightBudget()) return false
    add(ToolOutputHighlightSpan(start = start, end = end, kind = kind))
    return true
}

private fun List<ToolOutputHighlightSpan>.hasHighlightBudget(): Boolean =
    size < ToolOutputMaxHighlightSpans

private fun addIfClear(
    spans: MutableList<ToolOutputHighlightSpan>,
    occupied: BooleanArray,
    start: Int,
    end: Int,
    kind: ToolOutputHighlightKind,
) {
    if (start < 0 || end > occupied.size || occupied.anyInRange(start, end)) return
    if (spans.addValid(start, end, kind)) {
        occupied.mark(start, end)
    }
}

private fun BooleanArray.anyInRange(start: Int, end: Int): Boolean =
    (start.coerceAtLeast(0) until end.coerceAtMost(size)).any { this[it] }

private fun BooleanArray.mark(start: Int, end: Int) {
    (start.coerceAtLeast(0) until end.coerceAtMost(size)).forEach { this[it] = true }
}

private fun scanQuotedString(text: String, start: Int): Int {
    val quote = text[start]
    var index = start + 1
    var escaped = false
    while (index < text.length) {
        val char = text[index]
        when {
            escaped -> escaped = false
            char == '\\' -> escaped = true
            char == quote -> return index + 1
        }
        index++
    }
    return text.length
}

private fun isJsonObjectKey(text: String, stringEnd: Int): Boolean {
    var index = stringEnd
    while (index < text.length && text[index].isWhitespace()) {
        index++
    }
    return index < text.length && text[index] == ':'
}

private fun Char.isNumberStart(text: String, index: Int): Boolean =
    isDigit() || (this == '-' && text.charOrNull(index + 1)?.isDigit() == true)

private fun scanNumber(text: String, start: Int): Int {
    var index = start
    if (text.charOrNull(index) == '-') index++
    while (text.charOrNull(index)?.isDigit() == true) index++
    if (text.charOrNull(index) == '.') {
        index++
        while (text.charOrNull(index)?.isDigit() == true) index++
    }
    if (text.charOrNull(index) == 'e' || text.charOrNull(index) == 'E') {
        index++
        if (text.charOrNull(index) == '+' || text.charOrNull(index) == '-') index++
        while (text.charOrNull(index)?.isDigit() == true) index++
    }
    return index
}

private fun String.startsWithJsonLiteral(index: Int): String? =
    jsonLiterals.firstOrNull { literal ->
        startsWith(literal, startIndex = index) &&
            charOrNull(index + literal.length)?.let { it.isLetterOrDigit() || it == '_' } != true
    }

private fun String.charOrNull(index: Int): Char? =
    if (index in indices) this[index] else null

private fun String?.allowsHashComments(): Boolean =
    this == "bash" || this == "python" || this == "shell" || this == "sh"

private fun findShellCommandEnd(text: String, start: Int, lineEnd: Int): Int {
    var index = start
    while (index < lineEnd && !text[index].isWhitespace()) {
        index++
    }
    return index
}

private fun keywordRegex(languageHint: String?): Regex {
    val keywords = when (languageHint) {
        "kotlin" -> kotlinKeywords
        "python" -> pythonKeywords
        "javascript" -> javascriptKeywords
        "bash", "sh", "shell" -> shellKeywords
        else -> commonCodeKeywords
    }
    return Regex("""\b(${keywords.joinToString("|") { Regex.escape(it) }})\b""")
}

private val jsonLiterals = listOf("true", "false", "null")
private val codePunctuation = setOf('{', '}', '[', ']', '(', ')', '.', ',', ':', ';', '=', '+', '-', '*', '/', '<', '>')
private val codeNumberRegex = Regex("""\b\d+(?:\.\d+)?\b""")
private val codeFunctionRegex = Regex("""\b[A-Za-z_][A-Za-z0-9_]*(?=\s*\()""")
private val shellFlagRegex = Regex("""(?<=\s)-{1,2}[A-Za-z0-9][A-Za-z0-9_-]*""")
private val errorWordRegex = Regex("""\b(ERROR|ERR|FAILED|FAILURE|FAIL|FATAL|EXCEPTION|DENIED|CRASHED)\b""", RegexOption.IGNORE_CASE)
private val warningWordRegex = Regex("""\b(WARN|WARNING|CAUTION|SKIPPED)\b""", RegexOption.IGNORE_CASE)
private val successWordRegex = Regex("""\b(OK|SUCCESS|PASSED|DONE|CLEAN|COMPLETE|COMPLETED)\b""", RegexOption.IGNORE_CASE)
private val commonCodeKeywords = setOf(
    "as",
    "async",
    "await",
    "break",
    "catch",
    "class",
    "const",
    "continue",
    "def",
    "else",
    "false",
    "for",
    "fun",
    "function",
    "if",
    "import",
    "in",
    "let",
    "new",
    "null",
    "object",
    "package",
    "return",
    "throw",
    "true",
    "try",
    "val",
    "var",
    "when",
    "while",
)
private val kotlinKeywords = commonCodeKeywords + setOf(
    "data",
    "internal",
    "is",
    "override",
    "private",
    "sealed",
    "suspend",
)
private val pythonKeywords = commonCodeKeywords + setOf(
    "elif",
    "except",
    "finally",
    "from",
    "None",
    "pass",
    "raise",
    "with",
    "yield",
)
private val javascriptKeywords = commonCodeKeywords + setOf(
    "export",
    "extends",
    "interface",
    "this",
    "type",
)
private val shellKeywords = setOf(
    "cd",
    "cat",
    "cp",
    "echo",
    "find",
    "git",
    "grep",
    "ls",
    "mkdir",
    "mv",
    "npm",
    "pnpm",
    "rm",
    "rg",
    "sed",
    "yarn",
)

internal data class LimitedText(
    val text: String,
    val omittedLines: Int,
    val omittedChars: Int,
)

internal data class LimitedDiffFiles(
    val files: List<DiffFile>,
    val omittedLines: Int,
)

internal fun limitDiffFilesForRendering(
    files: List<DiffFile>,
    maxLines: Int = ToolOutputMaxRenderedLines,
): LimitedDiffFiles {
    if (maxLines <= 0) {
        return LimitedDiffFiles(files = emptyList(), omittedLines = files.sumOf { it.lines.size })
    }

    val limitedFiles = mutableListOf<DiffFile>()
    var usedLines = 0
    var omittedLines = 0
    files.forEach { file ->
        val remaining = maxLines - usedLines
        if (remaining <= 0) {
            omittedLines += file.lines.size
        } else {
            val visibleLines = file.lines.take(remaining)
            if (visibleLines.isNotEmpty()) {
                limitedFiles += file.copy(lines = visibleLines)
            }
            usedLines += visibleLines.size
            omittedLines += file.lines.size - visibleLines.size
        }
    }
    return LimitedDiffFiles(files = limitedFiles, omittedLines = omittedLines)
}

internal fun limitRenderedText(
    text: String,
    maxLines: Int = ToolOutputMaxRenderedLines,
    maxChars: Int = ToolOutputMaxRenderedChars,
): LimitedText {
    val lines = text.lines()
    val safeMaxLines = maxLines.coerceAtLeast(0)
    val safeMaxChars = maxChars.coerceAtLeast(0)
    val omittedLines = (lines.size - safeMaxLines).coerceAtLeast(0)
    val byLine = lines.take(safeMaxLines).joinToString("\n")
    val rendered = byLine.take(safeMaxChars)
    val omittedChars = (byLine.length - rendered.length).coerceAtLeast(0)
    return LimitedText(text = rendered, omittedLines = omittedLines, omittedChars = omittedChars)
}

private fun formatTable(rows: List<List<String>>): String {
    if (rows.isEmpty()) return ""
    val width = rows.maxOf { it.size }
    val columnWidths = (0 until width).map { column ->
        rows.maxOf { row -> row.getOrNull(column)?.length ?: 0 }
    }
    return rows.joinToString("\n") { row ->
        (0 until width).joinToString("  ") { column ->
            row.getOrNull(column).orEmpty().padEnd(columnWidths[column])
        }.trimEnd()
    }
}
