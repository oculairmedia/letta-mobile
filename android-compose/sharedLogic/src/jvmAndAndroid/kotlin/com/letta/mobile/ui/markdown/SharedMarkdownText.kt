package com.letta.mobile.ui.markdown

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import org.intellij.markdown.ast.ASTNode

fun interface MermaidDiagramRenderer {
    @Composable
    fun Render(source: String, modifier: Modifier)
}

val LocalMermaidDiagramRenderer = staticCompositionLocalOf<MermaidDiagramRenderer?> { null }

internal enum class CodeFenceRenderer {
    MermaidDiagram,
    HighlightedCode,
}

internal fun selectCodeFenceRenderer(language: String, source: String): CodeFenceRenderer =
    if (language.equals("mermaid", ignoreCase = true) && source.isNotBlank()) {
        CodeFenceRenderer.MermaidDiagram
    } else {
        CodeFenceRenderer.HighlightedCode
    }

/**
 * Common Android/Desktop Markdown paint adapter.
 *
 * Mobile keeps its mature extended renderer for math, Mermaid, images, and
 * accessibility while both platforms consume the extracted semantic document
 * model. Desktop uses this common renderer directly instead of raw Compose Text.
 */
@Composable
fun SharedMarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    if (text.isBlank()) return
    val repaired = remember(text) { repairIncompleteMarkdownForStreaming(text) }
    val isDark = isSystemInDarkTheme()
    val highlights = remember(isDark) {
        Highlights.Builder().theme(SyntaxThemes.atom(darkMode = isDark))
    }
    val mermaidRenderer = LocalMermaidDiagramRenderer.current
    val components = remember(highlights, mermaidRenderer) {
        markdownComponents(
            codeBlock = {
                MarkdownHighlightedCodeBlock(
                    content = it.content,
                    node = it.node,
                    highlightsBuilder = highlights,
                )
            },
            codeFence = {
                val (language, source) = extractCodeFenceInfo(it.content, it.node)
                when (selectCodeFenceRenderer(language, source)) {
                    CodeFenceRenderer.MermaidDiagram -> mermaidRenderer?.Render(
                        source = source,
                        modifier = Modifier.fillMaxWidth(),
                    ) ?: MarkdownHighlightedCodeFence(
                        content = it.content,
                        node = it.node,
                        highlightsBuilder = highlights,
                    )
                    CodeFenceRenderer.HighlightedCode -> MarkdownHighlightedCodeFence(
                        content = it.content,
                        node = it.node,
                        highlightsBuilder = highlights,
                    )
                }
            },
        )
    }
    Markdown(
        content = repaired,
        modifier = modifier.fillMaxWidth(),
        components = components,
        retainState = true,
        colors = markdownColor(
            text = textColor,
            codeBackground = MaterialTheme.colorScheme.surfaceVariant,
            inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
            dividerColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        typography = markdownTypography(
            text = MaterialTheme.typography.bodyMedium,
            code = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            h1 = MaterialTheme.typography.headlineSmall,
            h2 = MaterialTheme.typography.titleLarge,
            h3 = MaterialTheme.typography.titleMedium,
            h4 = MaterialTheme.typography.titleSmall,
            h5 = MaterialTheme.typography.bodyLarge,
            h6 = MaterialTheme.typography.bodyMedium,
        ),
    )
}

private fun extractCodeFenceInfo(content: String, node: ASTNode): Pair<String, String> {
    var language = ""
    val codeLines = mutableListOf<String>()

    for (child in node.children) {
        when (child.type.name) {
            "FENCE_LANG" -> language = content.substring(child.startOffset, child.endOffset).trim()
            "CODE_FENCE_CONTENT" -> codeLines.add(content.substring(child.startOffset, child.endOffset))
            "EOL" -> if (codeLines.isNotEmpty()) codeLines.add("\n")
        }
    }

    return language to codeLines.joinToString("").trimEnd()
}
