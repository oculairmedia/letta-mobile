package com.letta.mobile.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Android markdown renderer's type scale: one [Typography] role per markdown element, headings
 * emphasised one step, all in the caller's ink. Built from the theme's typography so it follows the
 * Android type scale and the chat font scale.
 */
@Immutable
data class MarkdownTypeScale(
    val text: TextStyle,
    val code: TextStyle,
    val h1: TextStyle,
    val h2: TextStyle,
    val h3: TextStyle,
    val h4: TextStyle,
    val h5: TextStyle,
    val h6: TextStyle,
    val quote: TextStyle,
    val bullet: TextStyle,
)

/** Named text and span styles for markdown and code content. */
object MarkdownTextStyles {
    /** Alpha of a block quote's ink against the body text: a quote reads one step quieter. */
    private const val QUOTE_INK_ALPHA = 0.7f

    /**
     * A fenced code block or a code source shown as text, tuned for ASCII-art alignment:
     * [LettaCodeFont] (full box-drawing coverage), `bodySmall` (at 11sp subpixel rounding drifts
     * column widths), zero letter spacing so every advance is one em-width, and ligatures off so
     * `=>`, `==`, `->` keep one cell per character.
     */
    fun codeBlock(typography: Typography, color: Color): TextStyle = typography.bodySmall.copy(
        fontFamily = LettaCodeFont,
        color = color,
        letterSpacing = 0.sp,
        fontFeatureSettings = "liga 0, calt 0",
    )

    /** Body text in [color]: a paragraph, a list item, the streaming tail. */
    fun body(typography: Typography, color: Color): TextStyle = typography.bodyMedium.copy(color = color)

    /** A table's header cells: the table's cell style (`bodySmall`) in semibold. */
    fun tableHeader(typography: Typography): TextStyle = typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)

    /** The markdown type scale in [textColor], with code blocks in [codeColor]. */
    fun scale(typography: Typography, textColor: Color, codeColor: Color): MarkdownTypeScale {
        val semibold = FontWeight.SemiBold
        val medium = FontWeight.Medium
        return MarkdownTypeScale(
            text = body(typography, textColor),
            code = codeBlock(typography, codeColor),
            h1 = typography.headlineSmall.copy(color = textColor, fontWeight = semibold),
            h2 = typography.titleLarge.copy(color = textColor, fontWeight = semibold),
            h3 = typography.titleMedium.copy(color = textColor, fontWeight = semibold),
            h4 = typography.titleSmall.copy(color = textColor, fontWeight = medium),
            h5 = typography.bodyLarge.copy(color = textColor, fontWeight = medium),
            h6 = typography.bodyMedium.copy(color = textColor, fontWeight = medium),
            quote = typography.bodyMedium.copy(color = textColor.copy(alpha = QUOTE_INK_ALPHA), fontWeight = medium),
            bullet = typography.bodyMedium.copy(color = textColor, fontWeight = medium),
        )
    }

    /** Strong emphasis inside a run of text (markdown `**bold**`, an unhighlighted code keyword). */
    val strong: SpanStyle = SpanStyle(fontWeight = FontWeight.Bold)

    /** Inline code inside a run of text: the code font on [background]. */
    fun inlineCode(background: Color): SpanStyle = SpanStyle(fontFamily = LettaCodeFont, background = background)
}

/** Geometry of markdown's inline-code chip, in sp so it scales with the text it sits in. */
object MarkdownInlineCodeDimens {
    val cornerRadius: TextUnit = 4.sp
    val horizontalPadding: TextUnit = 4.sp
    val verticalPadding: TextUnit = 2.sp

    /** Space kept above and below the chip so it does not touch the neighbouring lines. */
    val verticalMargin: TextUnit = 2.sp
}

/** Block sizes the markdown renderer owns. */
object MarkdownBlockDimens {
    /** Height an oversized a2ui-json fallback fence is collapsed to until the reader expands it. */
    val collapsedFallbackMaxHeight: Dp = 240.dp
}
