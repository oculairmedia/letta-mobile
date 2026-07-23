package com.letta.mobile.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import org.intellij.markdown.ast.ASTNode

fun interface MermaidDiagramRenderer {
    @Composable
    fun Render(source: String, modifier: Modifier)
}

val LocalMermaidDiagramRenderer = staticCompositionLocalOf<MermaidDiagramRenderer?> { null }

internal enum class CodeFenceRenderer {
    MermaidDiagram,
    Code,
}

internal fun selectCodeFenceRenderer(
    language: String,
    source: String,
    deferIncompleteMermaid: Boolean = false,
): CodeFenceRenderer =
    if (shouldRenderMermaidDiagram(language, source, deferIncompleteMermaid)) {
        CodeFenceRenderer.MermaidDiagram
    } else {
        CodeFenceRenderer.Code
    }

private fun shouldRenderMermaidDiagram(
    language: String,
    source: String,
    deferIncompleteMermaid: Boolean,
): Boolean {
    if (!language.equals("mermaid", ignoreCase = true)) return false
    if (source.isBlank()) return false
    if (deferIncompleteMermaid) return false
    return true
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
    retainState: Boolean = true,
) {
    if (text.isBlank()) return
    val repaired = remember(text) { repairIncompleteMarkdownForStreaming(text) }
    val retentionTracker = remember { MarkdownRetentionTracker() }
    val retentionKey = retentionTracker.update(text)
    val deferIncompleteMermaid = remember(text) { hasOpenMarkdownCodeFence(text) }
    val mermaidRenderer = LocalMermaidDiagramRenderer.current
    val components = remember(mermaidRenderer, deferIncompleteMermaid) {
        markdownComponents(
            codeBlock = {
                MarkdownCodeBlock(
                    content = it.content,
                    node = it.node,
                )
            },
            codeFence = {
                val (language, source) = extractCodeFenceInfo(it.content, it.node)
                when (selectCodeFenceRenderer(language, source, deferIncompleteMermaid)) {
                    CodeFenceRenderer.MermaidDiagram -> mermaidRenderer?.Render(
                        source = source,
                        modifier = Modifier.fillMaxWidth(),
                    ) ?: MarkdownCodeFence(
                        content = it.content,
                        node = it.node,
                    )
                    CodeFenceRenderer.Code -> MarkdownCodeFence(
                        content = it.content,
                        node = it.node,
                    )
                }
            },
        )
    }
    // The renderer's retained state intentionally paints the previous AST while
    // parsing an update. That is safe for append-only streaming, but a final
    // reconciliation can shorten or replace the text. In that case old AST
    // offsets may exceed the new content length and crash selectable desktop
    // text in ParagraphBuilder. Reset only on non-prefix changes so ordinary
    // streaming keeps the no-flicker retained-state path.
    key(retentionKey) {
        Markdown(
            content = repaired,
            modifier = modifier.fillMaxWidth(),
            components = components,
            retainState = retainState,
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
}

/**
 * Tracks when retained parser state can safely survive a content update.
 *
 * This is deliberately a plain remembered object rather than Compose state:
 * the returned revision is only a structural key for the renderer subtree.
 */
internal class MarkdownRetentionTracker {
    private var previousText: String? = null
    private var revision: Int = 0

    fun update(text: String): Int {
        val previous = previousText
        if (previous != null && !text.startsWith(previous)) revision++
        previousText = text
        return revision
    }
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
