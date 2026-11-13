package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.api.CodeSmell
import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.Entity
import org.jetbrains.kotlin.psi.KtFile

/**
 * Flags invisible and bidirectional-control characters typed literally anywhere in a Kotlin file
 * (strings, comments, identifiers), in main and test sources alike. They render as nothing, or
 * reorder the text around them, so the code a reviewer reads can differ from the code that runs;
 * SonarCloud rejects them.
 *
 * Two placements are not reported: a byte-order mark as the very first character (file encoding
 * metadata, not content) and a zero-width joiner between two emoji, which is how a multi-person or
 * skin-tone emoji is spelled and renders as one visible glyph.
 */
internal class InvisibleOrBidiCharacter(config: Config = Config.empty) : MobileRule(
    config,
    "InvisibleOrBidiCharacter",
    "Source must not contain literal invisible or bidirectional-control characters.",
) {
    override fun visitKtFile(file: KtFile) {
        super.visitKtFile(file)
        if (file.isGenerated()) return
        val text = file.text
        text.indices.filter { isReportable(text, it) }.forEach { offset ->
            val element = file.findElementAt(offset) ?: file
            val code = "\\u%04X".format(text[offset].code)
            report(
                CodeSmell(
                    issue,
                    Entity.from(element, offset - element.textRange.startOffset),
                    "Literal invisible or bidi character U+${code.drop(2)}: write it as the escape '$code' " +
                        "(or delete it) so reviewers can see it.",
                ),
            )
        }
    }

    private fun isReportable(text: String, offset: Int): Boolean {
        val char = text[offset]
        return when {
            !isInvisibleOrBidi(char) -> false
            char == BOM -> offset != 0
            else -> char != ZERO_WIDTH_JOINER || !joinsEmoji(text, offset)
        }
    }

    private fun joinsEmoji(text: String, offset: Int): Boolean =
        endsEmoji(text.getOrNull(offset - 1)) && startsEmoji(text.getOrNull(offset + 1))

    private fun endsEmoji(char: Char?): Boolean = when {
        char == null -> false
        char.isLowSurrogate() -> true
        else -> char == VARIATION_SELECTOR || char in SYMBOLS
    }

    private fun startsEmoji(char: Char?): Boolean = char != null && (char.isHighSurrogate() || char in SYMBOLS)

    // Built from code points so this file never contains the characters it reports.
    private companion object {
        val BOM = Char(0xFEFF)
        val ZERO_WIDTH_JOINER = Char(0x200D)
        val VARIATION_SELECTOR = Char(0xFE0F)
        val SYMBOLS = Char(0x2600)..Char(0x27BF)
        val RANGES = listOf(
            0x200B to 0x200F,
            0x202A to 0x202E,
            0x2060 to 0x2064,
            0x2066 to 0x2069,
            0xFEFF to 0xFEFF,
            0x061C to 0x061C,
        ).map { (low, high) -> Char(low)..Char(high) }

        fun isInvisibleOrBidi(char: Char): Boolean = RANGES.any { char in it }
    }
}
