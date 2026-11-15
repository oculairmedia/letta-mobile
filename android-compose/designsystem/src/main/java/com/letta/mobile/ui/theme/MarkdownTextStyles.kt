package com.letta.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Named text and span styles for the Android markdown renderer and code content: one typography
 * role per markdown element, headings emphasised one step, so call sites pass a named style rather
 * than overriding weights and families themselves. Each takes the theme's Typography, so it follows
 * the Android type scale and the chat font scale.
 */

/** Alpha of a block quote's ink against the body text: a quote reads one step quieter. */
private const val MARKDOWN_QUOTE_INK_ALPHA = 0.7f

/**
 * A fenced code block or a code source shown as text, tuned for ASCII-art alignment: [LettaCodeFont]
 * (full box-drawing coverage), `bodySmall` (at 11sp subpixel rounding drifts column widths), zero
 * letter spacing so every advance is one em-width, and ligatures off so `=>`, `==`, `->` keep one
 * cell per character.
 */
fun markdownCodeBlockStyle(typography: Typography, color: Color): TextStyle = typography.bodySmall.copy(
    fontFamily = LettaCodeFont,
    color = color,
    letterSpacing = 0.sp,
    fontFeatureSettings = "liga 0, calt 0",
)

/** Body text in [color]: a paragraph, a list item, the streaming tail. */
fun markdownBodyStyle(typography: Typography, color: Color): TextStyle = typography.bodyMedium.copy(color = color)

/** A block quote: body text, medium weight, one step quieter than [color]. */
fun markdownQuoteStyle(typography: Typography, color: Color): TextStyle =
    typography.bodyMedium.copy(color = color.copy(alpha = MARKDOWN_QUOTE_INK_ALPHA), fontWeight = FontWeight.Medium)

/** A list bullet: body text in medium weight. */
fun markdownBulletStyle(typography: Typography, color: Color): TextStyle =
    typography.bodyMedium.copy(color = color, fontWeight = FontWeight.Medium)

/**
 * Heading [level] (1-6) in [color]: h1-h3 are headlineSmall, titleLarge and titleMedium in semibold;
 * h4-h6 are titleSmall, bodyLarge and bodyMedium in medium weight.
 */
fun markdownHeadingStyle(level: Int, typography: Typography, color: Color): TextStyle = when (level) {
    1 -> typography.headlineSmall.copy(color = color, fontWeight = FontWeight.SemiBold)
    2 -> typography.titleLarge.copy(color = color, fontWeight = FontWeight.SemiBold)
    3 -> typography.titleMedium.copy(color = color, fontWeight = FontWeight.SemiBold)
    4 -> typography.titleSmall.copy(color = color, fontWeight = FontWeight.Medium)
    5 -> typography.bodyLarge.copy(color = color, fontWeight = FontWeight.Medium)
    else -> typography.bodyMedium.copy(color = color, fontWeight = FontWeight.Medium)
}

/** A table's header cells: the table's cell style (`bodySmall`) in semibold. */
fun markdownTableHeaderStyle(typography: Typography): TextStyle = typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)

/** Strong emphasis inside a run of text (markdown `**bold**`, an unhighlighted code keyword). */
val MarkdownStrongSpan: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold)

/** Inline code inside a run of text: the code font on [background]. */
fun markdownInlineCodeSpan(background: Color): SpanStyle = SpanStyle(fontFamily = LettaCodeFont, background = background)

/** Corner radius of markdown's inline-code chip, in sp so it scales with the text it sits in. */
val MarkdownInlineCodeCorner: TextUnit = 4.sp

/** Horizontal padding inside markdown's inline-code chip. */
val MarkdownInlineCodePaddingHorizontal: TextUnit = 4.sp

/** Vertical padding inside markdown's inline-code chip. */
val MarkdownInlineCodePaddingVertical: TextUnit = 2.sp

/** Space kept above and below markdown's inline-code chip so it does not touch neighbouring lines. */
val MarkdownInlineCodeMargin: TextUnit = 2.sp

/** Height an oversized a2ui-json fallback fence is collapsed to until the reader expands it. */
val MarkdownCollapsedFallbackMaxHeight: Dp = 240.dp
