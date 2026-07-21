package com.letta.mobile.feature.chat.render

/** True if the text contains an odd number of ``` fences (i.e. a code block is currently open). */
internal fun insideOpenCodeFence(text: String): Boolean {
    var count = 0
    var i = 0
    while (i <= text.length - 3) {
        if (isCodeFenceAt(text, i)) {
            count++
            i += 3
        } else {
            i++
        }
    }
    return count % 2 == 1
}

private fun isCodeFenceAt(text: String, index: Int): Boolean =
    text[index] == '`' && text[index + 1] == '`' && text[index + 2] == '`'

internal fun hasOpenDisplayMathFence(text: String): Boolean {
    var open = false
    var i = 0
    while (i <= text.length - 2) {
        if (text[i] == '\\') {
            i += 2
            continue
        }
        if (togglesDisplayMathFence(text, i)) {
            open = !open
            i += 2
            continue
        }
        i++
    }
    return open
}

private fun togglesDisplayMathFence(text: String, index: Int): Boolean {
    if (text.isInsideInlineCodeAt(index)) return false
    if (text[index] != '$') return false
    return text[index + 1] == '$'
}
