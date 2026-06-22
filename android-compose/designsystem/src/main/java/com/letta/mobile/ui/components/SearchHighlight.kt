package com.letta.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

@Immutable
data class SearchHighlightColors(
    val background: Color,
    val content: Color = Color.Unspecified,
)

@Composable
fun rememberSearchHighlightColors(
    background: Color = MaterialTheme.colorScheme.primaryContainer,
    content: Color = MaterialTheme.colorScheme.onPrimaryContainer,
): SearchHighlightColors = remember(background, content) {
    SearchHighlightColors(background = background, content = content)
}

fun highlightSearchMatches(
    text: String,
    query: String,
    colors: SearchHighlightColors,
    fontWeight: FontWeight = FontWeight.Bold,
): AnnotatedString = highlightSearchMatches(
    text = text,
    query = query,
    highlightColor = colors.background,
    matchTextColor = colors.content,
    fontWeight = fontWeight,
)

fun highlightSearchMatches(
    text: String,
    query: String,
    highlightColor: Color,
    matchTextColor: Color = Color.Unspecified,
    fontWeight: FontWeight = FontWeight.Bold,
): AnnotatedString = buildAnnotatedString {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isBlank()) {
        append(text)
        return@buildAnnotatedString
    }

    val lowerText = text.lowercase()
    val lowerQuery = trimmedQuery.lowercase()
    var cursor = 0
    while (cursor < text.length) {
        val matchIndex = lowerText.indexOf(lowerQuery, cursor)
        if (matchIndex < 0) {
            append(text.substring(cursor))
            break
        }

        append(text.substring(cursor, matchIndex))
        withStyle(
            SpanStyle(
                background = highlightColor,
                fontWeight = fontWeight,
                color = if (matchTextColor != Color.Unspecified) matchTextColor else Color.Unspecified,
            )
        ) {
            append(text.substring(matchIndex, matchIndex + lowerQuery.length))
        }
        cursor = matchIndex + lowerQuery.length
    }
}

fun searchResultSnippet(
    text: String,
    query: String,
    contextChars: Int = 96,
): String {
    val trimmedQuery = query.trim()
    if (text.length <= contextChars * 2 || trimmedQuery.isBlank()) return text

    val matchIndex = text.lowercase().indexOf(trimmedQuery.lowercase())
    if (matchIndex < 0) return text.take(contextChars * 2).trimEnd() + "..."

    val start = (matchIndex - contextChars).coerceAtLeast(0)
    val end = (matchIndex + trimmedQuery.length + contextChars).coerceAtMost(text.length)
    return buildString {
        if (start > 0) append("...")
        append(text.substring(start, end).trim())
        if (end < text.length) append("...")
    }
}
