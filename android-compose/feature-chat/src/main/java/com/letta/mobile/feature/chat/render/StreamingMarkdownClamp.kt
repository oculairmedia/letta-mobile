package com.letta.mobile.feature.chat.render

internal data class MarkdownClampSource(val raw: String)

internal data class MarkdownClampLineText(val value: String)

internal data class MarkdownClampLineIndex(val value: Int)

internal data class MarkdownClampLine(
    val text: MarkdownClampLineText,
    val startIndex: MarkdownClampLineIndex,
)

internal data class MarkdownClampRelativeIndex(val value: Int)

internal data class MarkdownClampCut(
    val source: MarkdownClampSource,
    val line: MarkdownClampLine,
    val relativeIndex: MarkdownClampRelativeIndex,
)

internal data class MarkdownClampScanResult(
    val source: MarkdownClampSource,
    val line: MarkdownClampLine,
)

/**
 * Returns the longest prefix of [source] that does NOT end inside an
 * open markdown construct. The returned prefix is what the renderer
 * can safely re-parse without producing a "raw markup flash" when
 * the construct's closer arrives in a later chunk.
 */
internal fun clampToStableMarkdown(source: MarkdownClampSource): String {
    val scan = MarkdownClampScanResult(source, source.lastLine())
    if (scan.line.text.value.isEmpty()) return unchangedSource(scan)
    clipAtUnmatchedMath(scan)?.let { return it }
    clipAtUnmatchedOpener(scan)?.let { return it }
    return unchangedSource(scan)
}

private fun unchangedSource(scan: MarkdownClampScanResult): String =
    scan.source.raw

private fun MarkdownClampSource.lastLine(): MarkdownClampLine {
    val lastBreak = raw.lastIndexOf('\n')
    val start = if (lastBreak < 0) 0 else lastBreak + 1
    return MarkdownClampLine(
        text = MarkdownClampLineText(raw.substring(start)),
        startIndex = MarkdownClampLineIndex(start),
    )
}

private fun clipAtUnmatchedMath(scan: MarkdownClampScanResult): String? {
    val unmatchedMathOpenIdx = findUnmatchedMathOpenerInLine(
        MarkdownMathScanLine(scan.line.text.value),
    )
    if (unmatchedMathOpenIdx < 0) return null
    return cutPrefix(
        MarkdownClampCut(
            scan.source,
            scan.line,
            MarkdownClampRelativeIndex(unmatchedMathOpenIdx),
        ),
    )
}

private fun clipAtUnmatchedOpener(scan: MarkdownClampScanResult): String? {
    val unmatchedOpenIdx = findUnmatchedOpenerInLine(
        MarkdownOpenerScanLine(scan.line.text.value),
    )
    if (unmatchedOpenIdx < 0) return null
    return cutPrefix(
        MarkdownClampCut(
            scan.source,
            scan.line,
            MarkdownClampRelativeIndex(unmatchedOpenIdx),
        ),
    )
}

private fun cutPrefix(cut: MarkdownClampCut): String =
    cut.source.raw.substring(0, cut.line.startIndex.value + cut.relativeIndex.value)
