package com.letta.mobile.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.compose.extendedspans.ExtendedSpans
import com.mikepenz.markdown.compose.extendedspans.RoundedCornerSpanPainter
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownExtendedSpans
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import org.intellij.markdown.ast.ASTNode
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LocalChatFontScale

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (text.isBlank()) return

    // Pre-pass: if the text contains display-math fences ($$…$$) OR a
    // plausible inline-math span ($…$), split into alternating Markdown /
    // MathBlock segments. Display-math is block-level (stacked column);
    // inline-math is interleaved with prose (wrapping row). Cheap fast-path
    // when neither marker is present.
    val hasDisplay = text.contains("$$")
    val hasInline = !hasDisplay && text.contains('$') && containsLikelyInlineMath(text)
    if (hasDisplay || hasInline) {
        val blockSegments = remember(text) { splitDisplayMathSegments(text) }
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

    MarkdownTextRaw(text = text, modifier = modifier, textColor = textColor)
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
    val pieces = remember(text) { splitInlineMathSegments(text) }
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        pieces.forEach { piece ->
            when (piece) {
                is MathSegment.Text ->
                    MarkdownTextRaw(text = piece.content, textColor = textColor)
                is MathSegment.Math ->
                    MathBlock(source = piece.content, displayMode = false)
            }
        }
    }
}

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

/** Internal renderer that handles a single non-math chunk via the Markdown lib. */
@Composable
private fun MarkdownTextRaw(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
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
        Markdown(
            content = text,
            modifier = modifier.fillMaxWidth(),
            components = components,
            extendedSpans = extendedSpans,
            imageTransformer = Coil3ImageTransformerImpl,
            colors = markdownColor(
                text = textColor,
                codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                dividerColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            typography = markdownTypography(
                text = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                code = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                h1 = MaterialTheme.typography.titleLarge.copy(color = textColor),
                h2 = MaterialTheme.typography.titleMedium.copy(color = textColor),
                h3 = MaterialTheme.typography.titleSmall.copy(color = textColor),
                h4 = MaterialTheme.typography.bodyLarge.copy(color = textColor),
                h5 = MaterialTheme.typography.bodyMedium.copy(color = textColor),
                h6 = MaterialTheme.typography.bodySmall.copy(color = textColor),
                quote = MaterialTheme.typography.bodyMedium.copy(
                    color = textColor.copy(alpha = 0.7f),
                ),
                bullet = MaterialTheme.typography.bodyMedium.copy(color = textColor),
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
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
