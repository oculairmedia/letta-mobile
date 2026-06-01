package com.letta.mobile.ui.components

import com.letta.mobile.ui.theme.LettaCodeFont

import android.util.Patterns
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.compose.Markdown as CoreMarkdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownExtendedSpans
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import org.intellij.markdown.ast.ASTNode
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LocalChatFontScale
import com.letta.mobile.ui.theme.scaledBy
import com.letta.mobile.ui.text.PreparedRichInlineItem
import com.letta.mobile.ui.text.RichInlineAtomKind
import com.letta.mobile.ui.text.RichInlineItem
import com.letta.mobile.ui.text.prepareRichInlineItems

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (text.isBlank()) return

    val renderText = remember(text) { exposeA2uiJsonTagsAsCodeFences(text) }
    if (renderText != text) {
        MarkdownTextRaw(text = renderText, modifier = modifier, textColor = textColor)
        return
    }

    // Pre-pass: if the text contains display-math fences ($$…$$) OR a
    // plausible inline-math span ($…$), split into alternating Markdown /
    // MathBlock segments. Display-math is block-level (stacked column);
    // inline-math is interleaved with prose (wrapping row). Cheap fast-path
    // when neither marker is present.
    val hasDisplay = renderText.contains("$$")
    val hasInline = !hasDisplay && renderText.contains('$') && containsLikelyInlineMath(renderText)
    if (hasDisplay || hasInline) {
        val blockSegments = remember(renderText) { splitDisplayMathSegments(renderText) }
        val hasBlockSplit = blockSegments.any { it is MathSegment.Math } && blockSegments.size > 1
        val hasAnyInline = blockSegments
            .filterIsInstance<MathSegment.Text>()
            .any { containsLikelyInlineMath(it.content) }

        if (hasBlockSplit || hasAnyInline) {
            androidx.compose.foundation.layout.Column(modifier = modifier) {
                blockSegments.forEach { seg ->
                    when (seg) {
                        is MathSegment.Text -> {
                            if (containsLikelyInlineMath(seg.content)) {
                                InlineMathParagraph(text = seg.content, textColor = textColor)
                            } else {
                                MarkdownTextRaw(
                                    text = seg.content,
                                    modifier = Modifier,
                                    textColor = textColor,
                                )
                            }
                        }
                        is MathSegment.Math ->
                            MathBlock(source = seg.content, displayMode = true)
                    }
                }
            }
            return
        }
    }

    MarkdownTextRaw(text = renderText, modifier = modifier, textColor = textColor)
}

/**
 * Renders a single paragraph that contains one or more inline `$…$` math
 * spans. Prose pieces go through [MarkdownTextRaw] (preserving markdown
 * formatting within each piece); math pieces go through [MathBlock] with
 * `displayMode=false`. The pieces are laid out in a [FlowRow] so long runs
 * still wrap across lines naturally.
 */
@Composable
private fun InlineMathParagraph(text: String, textColor: Color) {
    val pieces = remember(text) {
        prepareRichInlineItems(splitInlineMathSegments(text).toRichInlineItems())
    }
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        pieces.forEach { piece ->
            when (piece) {
                is PreparedRichInlineItem.Text ->
                    InlineRichTextChunk(text = piece.text, textColor = textColor)
                is PreparedRichInlineItem.Atom ->
                    when (piece.kind) {
                        RichInlineAtomKind.Math ->
                            MathBlock(source = piece.text, displayMode = false)
                        RichInlineAtomKind.Mention,
                        RichInlineAtomKind.Chip,
                        RichInlineAtomKind.Code ->
                            InlineRichTextChunk(text = piece.text, textColor = textColor)
                    }
            }
        }
    }
}

@Composable
private fun InlineRichTextChunk(text: String, textColor: Color) {
    if (text.isEmpty()) return
    if (text.hasInlineMarkdownSyntax()) {
        MarkdownTextRaw(text = text, textColor = textColor)
        return
    }

    val fontScale = LocalChatFontScale.current
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium.scaledBy(fontScale),
        color = textColor,
    )
}

private fun List<MathSegment>.toRichInlineItems(): List<RichInlineItem> =
    mapIndexedNotNull { index, segment ->
        when (segment) {
            is MathSegment.Text -> RichInlineItem.Text(segment.content)
            is MathSegment.Math -> RichInlineItem.Atom(
                id = "math-$index-${segment.content.hashCode()}",
                text = segment.content,
                kind = RichInlineAtomKind.Math,
            )
        }
    }

private fun String.hasInlineMarkdownSyntax(): Boolean =
    any { it in inlineMarkdownSyntaxChars }

private val inlineMarkdownSyntaxChars = setOf('*', '_', '`', '[', ']', '~')

/** Sealed segments produced by [splitDisplayMathSegments] and [splitInlineMathSegments]. */
internal sealed class MathSegment {
    data class Text(val content: String) : MathSegment()
    data class Math(val content: String) : MathSegment()
}

/**
 * Split [text] into alternating Markdown text and `$$…$$` math segments. Empty
 * text segments (between adjacent math blocks) are dropped.
 */
internal fun splitDisplayMathSegments(text: String): List<MathSegment> {
    val regex = Regex("\\$\\$([\\s\\S]+?)\\$\\$")
    val out = mutableListOf<MathSegment>()
    var last = 0
    for (match in regex.findAll(text)) {
        if (match.range.first > last) {
            val before = text.substring(last, match.range.first)
            if (before.isNotBlank()) out.add(MathSegment.Text(before))
        }
        val src = match.groupValues[1].trim()
        if (src.isNotEmpty()) out.add(MathSegment.Math(src))
        last = match.range.last + 1
    }
    if (last < text.length) {
        val tail = text.substring(last)
        if (tail.isNotBlank()) out.add(MathSegment.Text(tail))
    }
    return out
}

/**
 * Split a paragraph [text] into inline `$…$` math pieces interleaved with
 * Markdown text pieces.
 *
 * We intentionally use a narrower match than display-math to avoid false
 * positives on currency and shell variables:
 *  - The opening `$` must be at start-of-string or preceded by a non-word,
 *    non-`$` character — so `A$100` and `$$` won't anchor a match.
 *  - The opening `$` must NOT be followed by whitespace or a digit (`$ x$`
 *    and `$100` are skipped). KaTeX convention: real inline math starts
 *    with a non-space, non-digit character.
 *  - The closing `$` must NOT be preceded by whitespace.
 *  - The body must not contain another `$` or newline (inline math is
 *    single-line by convention; multi-line math belongs in `$$…$$`).
 *  - Empty bodies are rejected.
 *
 * Empty surrounding text segments are dropped, matching the display-math
 * splitter's contract. `$$` escapes are left for the display-math pass.
 */
internal fun splitInlineMathSegments(text: String): List<MathSegment> {
    val regex = Regex(INLINE_MATH_PATTERN)
    val out = mutableListOf<MathSegment>()
    var last = 0
    for (match in regex.findAll(text)) {
        val body = match.groupValues[1]
        if (body.isBlank()) continue
        // groupValues[1] is the math body, but match.range covers the full
        // `$body$` span including the fences.
        if (match.range.first > last) {
            val before = text.substring(last, match.range.first)
            if (before.isNotEmpty()) out.add(MathSegment.Text(before))
        }
        out.add(MathSegment.Math(body.trim()))
        last = match.range.last + 1
    }
    if (last < text.length) {
        val tail = text.substring(last)
        if (tail.isNotEmpty()) out.add(MathSegment.Text(tail))
    }
    // If no inline-math was found, return a single-element list so callers
    // can treat "no split" as "render as a single chunk" without branching.
    if (out.isEmpty()) out.add(MathSegment.Text(text))
    return out
}

/**
 * Cheap precheck used by [MarkdownText] to decide whether to invoke the
 * inline-math splitter at all. Mirrors [INLINE_MATH_PATTERN] but is an
 * anchored scan over `text`, so a false-positive here just costs one regex
 * match inside [splitInlineMathSegments].
 */
internal fun containsLikelyInlineMath(text: String): Boolean {
    if (!text.contains('$')) return false
    return Regex(INLINE_MATH_PATTERN).containsMatchIn(text)
}

// (?<=^|[^\w$]) — opening must follow a non-word, non-$ boundary
// \$           — literal $
// (?![\s\d])   — not followed by whitespace or digit (rules out $100, $ x$)
// ([^$\n]+?)   — body: no $ or newline, non-greedy
// (?<!\s)      — closing $ not preceded by whitespace
// \$           — literal closing $
// (?!\w)       — closing not followed by a word char (rules out `$x$abc`-style runs bleeding into identifiers)
private const val INLINE_MATH_PATTERN =
    "(?<=^|[^\\w$])\\$(?![\\s\\d])([^$\\n]+?)(?<!\\s)\\$(?!\\w)"

/**
 * Auto-linkify bare URLs in the text by wrapping them in markdown link syntax.
 * This pre-processes URLs so the markdown library can render them as clickable
 * LinkAnnotation.Url spans.
 *
 * Rules:
 * - Detects URLs via android.util.Patterns.WEB_URL
 * - Strips trailing punctuation (., ,, ), ], etc.) from detected URLs
 * - Skips URLs already inside markdown link syntax [text](url)
 * - Skips URLs inside inline code (`...`) and code fences (```...```)
 */
internal fun autolinkBareUrls(text: String): String {
    // Quick bail-out if no URL-like patterns exist
    if (!text.contains("http://") && !text.contains("https://") && !text.contains("www.")) {
        return text
    }

    // Find all code blocks and inline code spans to exclude from linkification
    val codeFenceRanges = findCodeFenceRanges(text)
    val inlineCodeRanges = findInlineCodeRanges(text)
    val markdownLinkRanges = findMarkdownLinkRanges(text)

    val result = StringBuilder()
    val urlMatcher = Patterns.WEB_URL.matcher(text)
    var lastEnd = 0

    while (urlMatcher.find()) {
        val urlStart = urlMatcher.start()
        val urlEnd = urlMatcher.end()
        var url = urlMatcher.group()

        // Skip if inside code fence, inline code, or markdown link
        val isInCodeFence = codeFenceRanges.any { it.contains(urlStart) }
        val isInInlineCode = inlineCodeRanges.any { it.contains(urlStart) }
        val isInMarkdownLink = markdownLinkRanges.any { it.contains(urlStart) }

        if (isInCodeFence || isInInlineCode || isInMarkdownLink) {
            continue
        }

        // Strip trailing punctuation that's often not part of the URL
        url = stripTrailingPunctuation(url)
        val actualUrlEnd = urlStart + url.length

        // Append text before this URL
        result.append(text.substring(lastEnd, urlStart))

        // Wrap in markdown link syntax
        result.append("[").append(url).append("](").append(url).append(")")

        lastEnd = actualUrlEnd
    }

    // Append remaining text
    result.append(text.substring(lastEnd))

    return result.toString()
}

internal fun exposeA2uiJsonTagsAsCodeFences(text: String): String {
    if (!text.contains("<a2ui-json", ignoreCase = true)) return text

    val ignoredRanges = findCodeFenceRanges(text) + findInlineCodeRanges(text)
    val result = StringBuilder()
    var lastEnd = 0
    var changed = false

    for (match in a2uiJsonTagRegex.findAll(text)) {
        val tagStart = match.range.first
        if (ignoredRanges.any { tagStart in it }) continue

        result.append(text.substring(lastEnd, tagStart))
        result.append(wrapA2uiJsonFallbackCodeFence(match.groupValues[1]))
        lastEnd = match.range.last + 1
        changed = true
    }

    if (!changed) return text
    result.append(text.substring(lastEnd))
    return result.toString()
}

private fun wrapA2uiJsonFallbackCodeFence(rawContent: String): String {
    val content = rawContent.trim().ifEmpty { "{}" }
    val fence = "`".repeat((content.longestBacktickRun() + 1).coerceAtLeast(3))
    return "\n\n${fence}a2ui-json\n$content\n$fence\n\n"
}

private fun String.longestBacktickRun(): Int {
    var longest = 0
    var current = 0
    forEach { char ->
        if (char == '`') {
            current += 1
            longest = maxOf(longest, current)
        } else {
            current = 0
        }
    }
    return longest
}

/**
 * Strip trailing punctuation commonly not part of URLs.
 * Common cases: "Check https://example.com." or "See (https://example.com)."
 */
private fun stripTrailingPunctuation(url: String): String {
    var cleaned = url
    while (cleaned.isNotEmpty() && cleaned.last() in setOf('.', ',', ')', ']', ';', ':', '!', '?')) {
        cleaned = cleaned.dropLast(1)
    }
    return cleaned
}

/** Find ranges of code fences (```...```) to exclude from URL linkification. */
private fun findCodeFenceRanges(text: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    val regex = Regex("""(?m)^(`{3,}|~{3,})[^\n]*\n[\s\S]*?^\1\s*$""")
    for (match in regex.findAll(text)) {
        ranges.add(match.range)
    }
    return ranges
}

/** Find ranges of inline code (`...`) to exclude from URL linkification. */
private fun findInlineCodeRanges(text: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    val regex = Regex("`[^`\n]+?`")
    for (match in regex.findAll(text)) {
        ranges.add(match.range)
    }
    return ranges
}

/** Find ranges of markdown link syntax [text](url) to avoid double-linkifying. */
private fun findMarkdownLinkRanges(text: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    // Match markdown link syntax: [text](url)
    val regex = Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)")
    for (match in regex.findAll(text)) {
        ranges.add(match.range)
    }
    return ranges
}

private val a2uiJsonTagRegex = Regex(
    pattern = """<a2ui-json\b[^>]*>([\s\S]*?)</a2ui-json>""",
    options = setOf(RegexOption.IGNORE_CASE),
)

/** Internal renderer that handles a single non-math chunk via the Markdown lib. */
@Composable
private fun MarkdownTextRaw(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    // Auto-linkify bare URLs before passing to the markdown renderer
    val linkedText = remember(text) { autolinkBareUrls(text) }
    val fontScale = LocalChatFontScale.current
    val isDarkTheme = isSystemInDarkTheme()

    val highlightsBuilder = remember(isDarkTheme) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDarkTheme))
    }

    val components = remember(highlightsBuilder) {
        markdownComponents(
            codeBlock = {
                MarkdownHighlightedCodeBlock(
                    content = it.content,
                    node = it.node,
                    highlightsBuilder = highlightsBuilder,
                )
            },
            codeFence = {
                val (language, codeText) = extractCodeFenceInfo(it.content, it.node)
                when {
                    language.equals("mermaid", ignoreCase = true) && codeText.isNotBlank() -> {
                        MermaidDiagram(source = codeText)
                    }
                    (language.equals("latex", ignoreCase = true) ||
                        language.equals("math", ignoreCase = true) ||
                        language.equals("tex", ignoreCase = true)) && codeText.isNotBlank() -> {
                        MathBlock(source = codeText, displayMode = true)
                    }
                    else -> {
                        CodeFenceWithHeader(
                            content = it.content,
                            node = it.node,
                            style = it.typography.code,
                            highlights = highlightsBuilder,
                        )
                    }
                }
            },
        )
    }

    val extendedSpans = markdownExtendedSpans {
        ExtendedSpans(
            RoundedCornerSpanPainter(
                cornerRadius = 4.sp,
                padding = RoundedCornerSpanPainter.TextPaddingValues(
                    horizontal = 4.sp,
                    vertical = 2.sp,
                ),
                topMargin = 2.sp,
                bottomMargin = 2.sp,
            ),
        )
    }

    // Override LocalDensity to scale all sp values for the Markdown library.
    // This works because sp → px conversion uses Density.fontScale, so changing
    // it effectively scales all text rendered inside the provider — including
    // the library's internal rendering that ignores external typography changes.
    val currentDensity = LocalDensity.current
    val scaledDensity = remember(currentDensity, fontScale) {
        Density(
            density = currentDensity.density,
            fontScale = currentDensity.fontScale * fontScale,
        )
    }

    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        CoreMarkdown(
            content = linkedText,
            modifier = modifier.fillMaxWidth(),
            components = components,
            extendedSpans = extendedSpans,
            imageTransformer = Coil3ImageTransformerImpl,
            retainState = true,
            colors = markdownColor(
                text = textColor,
                codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
                dividerColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            typography = markdownTypography(
                text = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                // letta-mobile-pcir: code style for fenced blocks. Tuned for
                // ASCII-art alignment:
                //   - JetBrains Mono via LettaCodeFont (full Unicode coverage
                //     including box-drawing glyphs).
                //   - bodySmall (12sp) instead of labelSmall (11sp) — at 11sp
                //     subpixel rounding makes adjacent column widths visibly
                //     drift even with a true monospace font.
                //   - letterSpacing = 0 so glyph-to-glyph advance stays a
                //     constant em-width.
                //   - liga/calt off via fontFeatureSettings — JetBrains Mono
                //     ligates `=>`, `==`, `!=`, `->`, etc. into composite
                //     glyphs that don't keep monospace cell width and visibly
                //     break ASCII art that uses these sequences.
                code = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = LettaCodeFont,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    letterSpacing = 0.sp,
                    fontFeatureSettings = "liga 0, calt 0",
                ),
                h1 = MaterialTheme.typography.headlineSmall.copy(
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                ),
                h2 = MaterialTheme.typography.titleLarge.copy(
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                ),
                h3 = MaterialTheme.typography.titleMedium.copy(
                    color = textColor,
                    fontWeight = FontWeight.SemiBold,
                ),
                h4 = MaterialTheme.typography.titleSmall.copy(
                    color = textColor,
                    fontWeight = FontWeight.Medium,
                ),
                h5 = MaterialTheme.typography.bodyLarge.copy(
                    color = textColor,
                    fontWeight = FontWeight.Medium,
                ),
                h6 = MaterialTheme.typography.bodyMedium.copy(
                    color = textColor,
                    fontWeight = FontWeight.Medium,
                ),
                quote = MaterialTheme.typography.bodyMedium.copy(
                    color = textColor.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Medium,
                ),
                bullet = MaterialTheme.typography.bodyMedium.copy(
                    color = textColor,
                    fontWeight = FontWeight.Medium,
                ),
                list = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                ordered = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            ),
        )
    }
}

@Composable
private fun CodeFenceWithHeader(
    content: String,
    node: ASTNode,
    style: TextStyle,
    highlights: Highlights.Builder,
) {
    val clipboardManager = LocalClipboardManager.current

    val (language, codeText) = remember(content, node) {
        extractCodeFenceInfo(content, node)
    }
    val capA2uiFallback = language.equals("a2ui-json", ignoreCase = true) &&
        (codeText.length > A2UI_JSON_FALLBACK_COLLAPSE_CHAR_LIMIT ||
            codeText.lineSequence().count() > A2UI_JSON_FALLBACK_COLLAPSE_LINE_LIMIT)
    var expanded by remember(content) { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column {
            if (language.isNotEmpty() || codeText.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = language.ifEmpty { "code" },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.weight(1f),
                    )
                    if (capA2uiFallback) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "Show less" else "Show all")
                        }
                    }
                    IconButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(codeText))
                        },
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.7f,
                            ),
                        ),
                    ) {
                        Icon(
                            imageVector = LettaIcons.Copy,
                            contentDescription = "Copy code",
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }

            // NOTE: do NOT wrap MarkdownHighlightedCodeFence in a horizontalScroll
            // here — the library already applies its own horizontalScroll internally.
            // Nesting two horizontal scrollers produces infinite-width constraints
            // during Compose's lookahead measure pass, which crashes the app with
            // "Horizontally scrollable component was measured with an infinity
            // maximum width constraints" (seen when scrolling back to messages
            // that contain fenced code blocks). See letta-mobile-o2v7 followup.
            // letta-mobile-pcir: center the code surface horizontally so a
            // narrow ASCII diagram (e.g. a 30-char tree) sits in the middle
            // of the fence rather than left-anchored against a wide gutter.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (capA2uiFallback && !expanded) {
                            Modifier
                                .heightIn(max = A2UI_JSON_FALLBACK_COLLAPSED_MAX_HEIGHT)
                                .verticalScroll(scrollState)
                        } else {
                            Modifier
                        }
                    )
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                MarkdownHighlightedCodeFence(
                    content = content,
                    node = node,
                    highlightsBuilder = highlights,
                )
            }
        }
    }
}

private fun extractCodeFenceInfo(content: String, node: ASTNode): Pair<String, String> {
    var language = ""
    val codeLines = mutableListOf<String>()

    for (child in node.children) {
        when (child.type.name) {
            "FENCE_LANG" -> {
                language = content.substring(child.startOffset, child.endOffset).trim()
            }

            "CODE_FENCE_CONTENT" -> {
                codeLines.add(content.substring(child.startOffset, child.endOffset))
            }

            "EOL" -> {
                if (codeLines.isNotEmpty()) {
                    codeLines.add("\n")
                }
            }
        }
    }

    val codeText = codeLines.joinToString("").trimEnd()
    return language to codeText
}

private val A2UI_JSON_FALLBACK_COLLAPSED_MAX_HEIGHT = 240.dp
private const val A2UI_JSON_FALLBACK_COLLAPSE_CHAR_LIMIT = 1_600
private const val A2UI_JSON_FALLBACK_COLLAPSE_LINE_LIMIT = 18
