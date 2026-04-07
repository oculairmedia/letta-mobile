package com.letta.mobile.ui.components

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val annotated = remember(text) { parseMarkdown(text, textColor) }
    SelectionContainer {
        Text(
            text = annotated,
            modifier = modifier,
            style = MaterialTheme.typography.bodyLarge.copy(color = textColor),
        )
    }
}

private fun parseMarkdown(text: String, defaultColor: Color): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val src = text
        while (i < src.length) {
            when {
                src.startsWith("```", i) -> {
                    val endIdx = src.indexOf("```", i + 3)
                    if (endIdx != -1) {
                        val codeContent = src.substring(i + 3, endIdx).trimStart { it != '\n' }.removePrefix("\n")
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified)) {
                            append(codeContent)
                        }
                        i = endIdx + 3
                    } else {
                        append(src[i])
                        i++
                    }
                }
                src.startsWith("**", i) -> {
                    val endIdx = src.indexOf("**", i + 2)
                    if (endIdx != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(src.substring(i + 2, endIdx))
                        }
                        i = endIdx + 2
                    } else {
                        append(src[i])
                        i++
                    }
                }
                src.startsWith("*", i) && (i + 1 < src.length && src[i + 1] != '*') -> {
                    val endIdx = src.indexOf("*", i + 1)
                    if (endIdx != -1) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(src.substring(i + 1, endIdx))
                        }
                        i = endIdx + 1
                    } else {
                        append(src[i])
                        i++
                    }
                }
                src.startsWith("`", i) && !src.startsWith("```", i) -> {
                    val endIdx = src.indexOf("`", i + 1)
                    if (endIdx != -1) {
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                            append(src.substring(i + 1, endIdx))
                        }
                        i = endIdx + 1
                    } else {
                        append(src[i])
                        i++
                    }
                }
                else -> {
                    append(src[i])
                    i++
                }
            }
        }
    }
}
