package com.letta.mobile.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.mikepenz.markdown.model.ReferenceLinkHandler
import com.mikepenz.markdown.model.ReferenceLinkHandlerImpl
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.findChildOfType
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.entities.EntityConverter
import org.intellij.markdown.parser.MarkdownParser

/**
 * The link reference definitions of a whole message (`[1]: https://example.com`), by label, keyed
 * and unescaped as the markdown renderer itself stores and looks them up. A renderer that paints a
 * message block by block, each block in its own markdown pass, needs them: a definition sits in a
 * block of its own (it follows a blank line), so without the message's definitions a block's
 * `[docs][1]` has no destination.
 */
internal fun markdownReferenceDefinitions(text: String): Map<String, String> {
    if (!text.contains(DEFINITION_MARK)) return emptyMap()
    val root = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(text)
    return mutableMapOf<String, String>().also { collectDefinitions(root, text, it) }
}

private fun collectDefinitions(node: ASTNode, text: String, into: MutableMap<String, String>) {
    if (node.type != MarkdownElementTypes.LINK_DEFINITION) {
        node.children.forEach { collectDefinitions(it, text, into) }
        return
    }
    val label = node.findChildOfType(MarkdownElementTypes.LINK_LABEL)?.unescapedText(text)
    val destination = node.findChildOfType(MarkdownElementTypes.LINK_DESTINATION)?.unescapedText(text)
    if (label != null && destination != null) into[label] = destination
}

private fun ASTNode.unescapedText(text: String): String =
    EntityConverter.replaceEntities(getTextInNode(text), processEntities = true, processEscapes = true)

/** The reference definitions of the message whose blocks render inside; empty outside one. */
private val LocalMessageReferenceDefinitions = staticCompositionLocalOf<Map<String, String>> { emptyMap() }

/**
 * Renders [content], a message's blocks, each in its own markdown pass, with the message's reference
 * [definitions]: a block's [rememberMessageReferenceLinks] resolves against them.
 */
@Composable
fun MessageReferenceDefinitions(definitions: Map<String, String>, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMessageReferenceDefinitions provides definitions, content = content)
}

/**
 * A block's link handler: the definitions its own markdown pass stores, and those of the rest of its
 * message ([MessageReferenceDefinitions]). One per block, so blocks never write into each other's links.
 */
@Composable
fun rememberMessageReferenceLinks(): ReferenceLinkHandler {
    val definitions = LocalMessageReferenceDefinitions.current
    return remember(definitions) { MessageReferenceLinks(definitions) }
}

private class MessageReferenceLinks(private val definitions: Map<String, String>) : ReferenceLinkHandler {
    private val own = ReferenceLinkHandlerImpl()

    override fun store(label: String, destination: String?) = own.store(label, destination)

    override fun find(label: String): String = definitions[label] ?: own.find(label)
}

/** Every link reference definition has a label closed by this. */
private const val DEFINITION_MARK = "]:"
