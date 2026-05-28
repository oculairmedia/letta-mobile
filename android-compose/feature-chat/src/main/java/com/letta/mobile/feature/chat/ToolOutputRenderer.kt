package com.letta.mobile.feature.chat

import com.letta.mobile.ui.theme.LettaCodeFont

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.tooloutput.DiffLine
import com.letta.mobile.data.tooloutput.DiffLineType
import com.letta.mobile.data.tooloutput.ToolOutputBlock
import com.letta.mobile.data.tooloutput.ToolOutputDocument
import com.letta.mobile.data.tooloutput.ToolOutputParser
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.text.ChatTextLayoutMode
import com.letta.mobile.ui.text.ChatTextVisualClip
import com.letta.mobile.ui.text.rememberChatTextGeometryMeasurer
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.LocalChatIsPinching
import com.letta.mobile.ui.theme.chatTypography
import com.letta.mobile.ui.theme.listItemSupporting
import com.letta.mobile.ui.theme.scaledBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val ToolOutputBackgroundParseThresholdChars = 12_000
internal const val ToolOutputBackgroundHighlightThresholdChars = 4_000
internal const val ToolOutputMaxRenderedChars = 20_000
internal const val ToolOutputMaxRenderedLines = 320
internal const val ToolOutputPreviewMaxRenderedChars = 1_200
internal const val ToolOutputMaxHighlightSpans = 800
internal const val ToolOutputDocumentMaxCacheableRawChars = ToolOutputParser.MaxAnalyzedChars

@Composable
@OptIn(ExperimentalAnimationApi::class)
internal fun ToolOutputRenderer(
    raw: String,
    expanded: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val rawCacheKey = remember(raw) { raw.toolOutputContentKey() }
    val cachedDocument = remember(rawCacheKey) { ToolOutputCaches.getDocument(rawCacheKey) }
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
    // during pinch by keeping the AnimatedContent wrapper mounted and swapping
    // to instant transitions. That avoids both height interpolation cascades
    // during multi-touch and content remounts on finger-up.
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
        AnimatedContent(
            targetState = expanded,
            modifier = Modifier.fillMaxWidth(),
            transitionSpec = {
                if (isPinching) {
                    (ChatMotion.instantEnter() togetherWith ChatMotion.instantExit())
                        .using(SizeTransform(clip = true) { _, _ -> ChatMotion.instantSizeSpec })
                } else {
                    // letta-mobile-vui8q: unfurl from leading edge instead of plain vertical expand.
                    (ChatMotion.unfurlEnter() togetherWith ChatMotion.unfurlExit())
                        .using(SizeTransform(clip = true) { _, _ -> ChatMotion.contentSizeSpec })
                }
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
    val syntaxColors = toolOutputSyntaxColors(isError)
    val textStyle = toolOutputTextStyle()
    val geometryMeasurer = rememberChatTextGeometryMeasurer()
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    var measuredContentWidthPx by remember { mutableIntStateOf(0) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        tonalElevation = 0.dp,
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            val constrainedWidthPx = remember(maxWidth, density) {
                if (maxWidth.value.isFinite()) {
                    with(density) { maxWidth.roundToPx() }
                } else {
                    0
                }
            }
            val contentWidthPx = measuredContentWidthPx.takeIf { it > 0 } ?: constrainedWidthPx
            val limited = remember(
                text,
                maxRenderedLines,
                maxRenderedChars,
                contentWidthPx,
                textStyle,
                density.density,
                density.fontScale,
                layoutDirection,
                geometryMeasurer,
            ) {
                if (contentWidthPx > 0) {
                    geometryMeasurer.clipToVisualLines(
                        text = text,
                        style = textStyle,
                        widthPx = contentWidthPx,
                        density = density,
                        layoutDirection = layoutDirection,
                        mode = ChatTextLayoutMode.Code,
                        maxLines = maxRenderedLines,
                        maxChars = maxRenderedChars,
                    ).toLimitedText()
                } else {
                    limitRenderedText(text, maxLines = maxRenderedLines, maxChars = maxRenderedChars)
                }
            }
            val highlightInBackground = limited.text.length > ToolOutputBackgroundHighlightThresholdChars &&
                highlightMode != ToolOutputHighlightMode.None
            val highlightCacheKey = remember(limited.text, highlightMode, languageHint) {
                ToolOutputHighlightCacheKey(
                    content = limited.text.toolOutputContentKey(),
                    mode = highlightMode,
                    languageHint = languageHint,
                )
            }
            val cachedSpans = remember(highlightCacheKey) { ToolOutputCaches.getHighlightSpans(highlightCacheKey) }
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size -> measuredContentWidthPx = size.width },
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                MonospaceText(
                    text = annotatedText,
                    color = syntaxColors.default,
                    maxLines = maxLines,
                    style = textStyle,
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
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = LettaCodeFont)
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
    style: TextStyle = toolOutputTextStyle(),
) {
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        softWrap = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun toolOutputTextStyle(): TextStyle =
    MaterialTheme.typography.listItemSupporting
        .copy(fontFamily = MaterialTheme.chatTypography.codeBlock.fontFamily ?: LettaCodeFont)
        .scaledBy(LocalChatFontScale.current)

@Composable
private fun ToolOutputLimitNotice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.scaledBy(LocalChatFontScale.current),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
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

private fun ChatTextVisualClip.toLimitedText(): LimitedText =
    LimitedText(
        text = text,
        omittedLines = omittedLines,
        omittedChars = omittedChars,
    )
