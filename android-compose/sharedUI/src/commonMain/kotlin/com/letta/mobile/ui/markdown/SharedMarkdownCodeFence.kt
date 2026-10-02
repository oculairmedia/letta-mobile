package com.letta.mobile.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
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
): CodeFenceRenderer {
    return if (shouldRenderMermaidDiagram(language, source, deferIncompleteMermaid)) {
        CodeFenceRenderer.MermaidDiagram
    } else {
        CodeFenceRenderer.Code
    }
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

/** Code blocks as the renderer draws them; code fences too, but a complete Mermaid fence as a diagram. */
@Composable
internal fun rememberSharedMarkdownComponents(text: String): MarkdownComponents {
    val deferIncompleteMermaid = remember(text) { hasOpenMarkdownCodeFence(text) }
    val mermaidRenderer = LocalMermaidDiagramRenderer.current
    return remember(mermaidRenderer, deferIncompleteMermaid) {
        markdownComponents(
            codeBlock = {
                MarkdownCodeBlock(
                    content = it.content,
                    node = it.node,
                )
            },
            codeFence = { SharedCodeFence(it, mermaidRenderer, deferIncompleteMermaid) },
        )
    }
}

@Composable
private fun SharedCodeFence(
    model: MarkdownComponentModel,
    mermaidRenderer: MermaidDiagramRenderer?,
    deferIncompleteMermaid: Boolean,
) {
    val (language, source) = extractCodeFenceInfo(model.content, model.node)
    when (selectCodeFenceRenderer(language, source, deferIncompleteMermaid)) {
        CodeFenceRenderer.MermaidDiagram -> mermaidRenderer?.Render(
            source = source,
            modifier = Modifier.fillMaxWidth(),
        ) ?: MarkdownCodeFence(
            content = model.content,
            node = model.node,
        )
        CodeFenceRenderer.Code -> MarkdownCodeFence(
            content = model.content,
            node = model.node,
        )
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
