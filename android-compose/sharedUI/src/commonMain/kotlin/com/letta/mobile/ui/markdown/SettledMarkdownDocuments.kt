package com.letta.mobile.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * [source]'s block document, from [state]. A settled text (not [isStreaming]) on a chat page starts
 * from the document it was parsed into before, when its row scrolled away and back or the page moved
 * between docked and full screen, so composition does not parse it again; a streaming text always
 * goes through [state], which keeps its block identities as it grows.
 */
@Composable
fun rememberStreamingMarkdownDocument(
    source: MarkdownSource,
    state: StreamingMarkdownDocumentState,
    isStreaming: Boolean,
): StreamingMarkdownDocument {
    val parses = LocalSettledMarkdownParses.current?.takeUnless { isStreaming }
    return remember(source, parses) {
        parses?.document(source) ?: state.update(source.text).also { parses?.keepDocument(source, it) }
    }
}

/**
 * [source] parsed as a table, or null when it is not one. A [settled] table on a chat page parses
 * once and is reused wherever its row composes again; one still streaming parses on each change.
 */
@Composable
fun rememberMarkdownTable(source: MarkdownSource, settled: Boolean): ParsedMarkdownTable? {
    val parses = LocalSettledMarkdownParses.current?.takeIf { settled }
    return remember(source, parses) {
        parses?.table(source) ?: parseMarkdownTable(source.text)?.also { parses?.keepTable(source, it) }
    }
}
