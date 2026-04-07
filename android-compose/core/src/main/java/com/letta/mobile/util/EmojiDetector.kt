package com.letta.mobile.util

private val EMOJI_REGEX = Regex(
    "[\\p{So}\\p{Sk}\\x{1F300}-\\x{1F9FF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}" +
        "\\x{FE00}-\\x{FE0F}\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]+"
)

fun isEmojiOnly(text: String): Boolean {
    val stripped = text.replace("\\s".toRegex(), "")
    if (stripped.isEmpty()) return false
    return EMOJI_REGEX.replace(stripped, "").isEmpty()
}

fun emojiCount(text: String): Int {
    val stripped = text.replace("\\s".toRegex(), "")
    if (stripped.isEmpty()) return 0
    val codePoints = stripped.codePoints().toArray()
    return codePoints.count { cp ->
        Character.getType(cp).let { type ->
            type == Character.OTHER_SYMBOL.toInt() ||
                type == Character.MODIFIER_SYMBOL.toInt() ||
                cp in 0x1F300..0x1F9FF ||
                cp in 0x2600..0x26FF ||
                cp in 0x2700..0x27BF
        }
    }
}
