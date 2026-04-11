package com.letta.mobile.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.LocalMarkdownTypography
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

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (text.isBlank()) return

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
                    highlights = highlightsBuilder,
                )
            },
            codeFence = {
                CodeFenceWithHeader(
                    content = it.content,
                    node = it.node,
                    style = it.typography.code,
                    highlights = highlightsBuilder,
                )
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

    Markdown(
        content = text,
        modifier = modifier.fillMaxWidth(),
        components = components,
        extendedSpans = extendedSpans,
        colors = markdownColor(
            text = textColor,
            codeText = MaterialTheme.colorScheme.onSurfaceVariant,
            codeBackground = MaterialTheme.colorScheme.surfaceVariant,
            dividerColor = MaterialTheme.colorScheme.outlineVariant,
            linkText = MaterialTheme.colorScheme.primary,
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
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = "Copy code",
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            ) {
                MarkdownHighlightedCodeFence(
                    content = content,
                    node = node,
                    highlights = highlights,
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
