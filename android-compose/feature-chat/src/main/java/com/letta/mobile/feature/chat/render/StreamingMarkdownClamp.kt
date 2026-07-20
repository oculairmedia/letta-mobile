package com.letta.mobile.feature.chat.render

import androidx.annotation.VisibleForTesting

/**
 * Returns the longest prefix of [raw] that does NOT end inside an
 * open markdown construct. The returned prefix is what the renderer
 * can safely re-parse without producing a "raw markup flash" when
 * the construct's closer arrives in a later chunk.
 */
@VisibleForTesting
internal fun clampToStableMarkdown(raw: String): String {
    val lastBreak = raw.lastIndexOf('\n')
    val lineStart = if (lastBreak < 0) 0 else lastBreak + 1
    val line = raw.substring(lineStart)
    if (line.isEmpty()) return raw

    val mathClip = clipAtUnmatchedMath(raw, lineStart, line)
    if (mathClip != null) return mathClip

    val openerClip = clipAtUnmatchedOpener(raw, lineStart, line)
    if (openerClip != null) return openerClip

    return raw
}

private fun clipAtUnmatchedMath(raw: String, lineStart: Int, line: String): String? {
    val unmatchedMathOpenIdx = findUnmatchedMathOpenerInLine(line)
    if (unmatchedMathOpenIdx < 0) return null
    return raw.substring(0, lineStart + unmatchedMathOpenIdx)
}

private fun clipAtUnmatchedOpener(raw: String, lineStart: Int, line: String): String? {
    val unmatchedOpenIdx = findUnmatchedOpenerInLine(line)
    if (unmatchedOpenIdx < 0) return null
    return raw.substring(0, lineStart + unmatchedOpenIdx)
}
