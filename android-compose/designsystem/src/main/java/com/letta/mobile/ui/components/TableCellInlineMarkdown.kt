package com.letta.mobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.letta.mobile.ui.theme.LettaCodeFont

/**
 * Lightweight inline markdown for table cells.
 *
 * Do not route cells through MarkdownTextRaw/CoreMarkdown here. Tables can update while streaming,
 * and the full markdown renderer is known to tear down and rebuild its subtree at paint cadence.
 */
internal fun buildTableCellAnnotatedString(
    source: String,
    inlineCodeBackground: Color = Color.Unspecified,
): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < source.length) {
        if (source[index] == '\\' && index + 1 < source.length) {
            append(source[index + 1])
            index += 2
            continue
        }

        val parsed = source.parseTableCellInlineSpan(index)
        if (parsed != null) {
            withStyle(parsed.style(inlineCodeBackground)) {
                append(parsed.text)
            }
            index = parsed.endExclusive
        } else {
            append(source[index])
            index++
        }
    }
}

private data class TableCellInlineSpan(
    val text: String,
    val endExclusive: Int,
    val kind: TableCellInlineSpanKind,
) {
    fun style(inlineCodeBackground: Color): SpanStyle =
        when (kind) {
            TableCellInlineSpanKind.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
            TableCellInlineSpanKind.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
            TableCellInlineSpanKind.Code -> SpanStyle(
                fontFamily = LettaCodeFont,
                background = inlineCodeBackground,
            )
            TableCellInlineSpanKind.Strikethrough -> SpanStyle(textDecoration = TextDecoration.LineThrough)
            TableCellInlineSpanKind.Link -> SpanStyle(textDecoration = TextDecoration.Underline)
        }
}

private enum class TableCellInlineSpanKind {
    Bold,
    Italic,
    Code,
    Strikethrough,
    Link,
}

private fun String.parseTableCellInlineSpan(start: Int): TableCellInlineSpan? {
    if (start !in indices) return null
    return when {
        this[start] == '`' -> parseDelimitedTableCellSpan(
            start = start,
            marker = "`",
            kind = TableCellInlineSpanKind.Code,
            allowWordBoundary = false,
        )
        startsWith("**", start) -> parseDelimitedTableCellSpan(
            start = start,
            marker = "**",
            kind = TableCellInlineSpanKind.Bold,
            allowWordBoundary = false,
        )
        startsWith("__", start) -> parseDelimitedTableCellSpan(
            start = start,
            marker = "__",
            kind = TableCellInlineSpanKind.Bold,
            allowWordBoundary = true,
        )
        startsWith("~~", start) -> parseDelimitedTableCellSpan(
            start = start,
            marker = "~~",
            kind = TableCellInlineSpanKind.Strikethrough,
            allowWordBoundary = false,
        )
        this[start] == '*' -> parseDelimitedTableCellSpan(
            start = start,
            marker = "*",
            kind = TableCellInlineSpanKind.Italic,
            allowWordBoundary = true,
        )
        this[start] == '_' -> parseDelimitedTableCellSpan(
            start = start,
            marker = "_",
            kind = TableCellInlineSpanKind.Italic,
            allowWordBoundary = true,
        )
        this[start] == '[' -> parseTableCellLinkSpan(start)
        else -> null
    }
}

private fun String.parseDelimitedTableCellSpan(
    start: Int,
    marker: String,
    kind: TableCellInlineSpanKind,
    allowWordBoundary: Boolean,
): TableCellInlineSpan? {
    if (!startsWith(marker, start)) return null
    if (allowWordBoundary && start > 0 && this[start - 1].isLetterOrDigit()) return null

    val contentStart = start + marker.length
    if (contentStart >= length || this[contentStart].isWhitespace()) return null

    val end = findClosingTableCellMarker(
        marker = marker,
        from = contentStart,
        allowWordBoundary = allowWordBoundary,
    ) ?: return null
    if (end <= contentStart || this[end - 1].isWhitespace()) return null

    return TableCellInlineSpan(
        text = substring(contentStart, end),
        endExclusive = end + marker.length,
        kind = kind,
    )
}

private fun String.findClosingTableCellMarker(
    marker: String,
    from: Int,
    allowWordBoundary: Boolean,
): Int? {
    var index = from
    while (index < length) {
        val candidate = indexOf(marker, startIndex = index)
        if (candidate < 0) return null
        if (!isEscapedAt(candidate) &&
            (!allowWordBoundary ||
                candidate + marker.length >= length ||
                !this[candidate + marker.length].isLetterOrDigit())
        ) {
            return candidate
        }
        index = candidate + marker.length
    }
    return null
}

private fun String.parseTableCellLinkSpan(start: Int): TableCellInlineSpan? {
    val labelEnd = findUnescaped(']', start + 1) ?: return null
    if (labelEnd + 1 >= length || this[labelEnd + 1] != '(') return null
    val urlEnd = findUnescaped(')', labelEnd + 2) ?: return null
    val label = substring(start + 1, labelEnd)
    val url = substring(labelEnd + 2, urlEnd)
    if (label.isBlank() || url.isBlank()) return null
    return TableCellInlineSpan(
        text = label,
        endExclusive = urlEnd + 1,
        kind = TableCellInlineSpanKind.Link,
    )
}

private fun String.findUnescaped(target: Char, from: Int): Int? {
    var index = from
    while (index < length) {
        val candidate = indexOf(target, startIndex = index)
        if (candidate < 0) return null
        if (!isEscapedAt(candidate)) return candidate
        index = candidate + 1
    }
    return null
}

private fun String.isEscapedAt(index: Int): Boolean {
    var slashCount = 0
    var i = index - 1
    while (i >= 0 && this[i] == '\\') {
        slashCount++
        i--
    }
    return slashCount % 2 == 1
}
