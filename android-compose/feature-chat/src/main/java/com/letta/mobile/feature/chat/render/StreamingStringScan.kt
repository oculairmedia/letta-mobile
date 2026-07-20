package com.letta.mobile.feature.chat.render

internal fun Char.isWordChar(): Boolean = isLetterOrDigit() || this == '_'

internal fun String.isSingleDollarAt(index: Int): Boolean {
    if (this[index] != '$' || isEscapedAt(index)) return false
    if (index > 0 && this[index - 1] == '$') return false
    if (index + 1 < length && this[index + 1] == '$') return false
    return true
}

internal fun String.isEscapedAt(index: Int): Boolean {
    var slashCount = 0
    var i = index - 1
    while (i >= 0 && this[i] == '\\') {
        slashCount++
        i--
    }
    return slashCount % 2 == 1
}

internal fun String.isInsideInlineCodeAt(index: Int): Boolean {
    var open = false
    var i = 0
    while (i < index) {
        if (this[i] == '`' && !isEscapedAt(i)) {
            open = !open
        }
        i++
    }
    return open
}
