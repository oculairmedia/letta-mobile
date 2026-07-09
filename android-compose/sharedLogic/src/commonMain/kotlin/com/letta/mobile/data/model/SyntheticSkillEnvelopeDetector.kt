package com.letta.mobile.data.model

object SyntheticSkillEnvelopeDetector {
    private const val MIN_ENVELOPE_CHARS = 200
    private const val FRONTMATTER_SCAN_LINES = 10
    private val openingSlugTag = Regex("""^<[a-z0-9-]+>\s*$""")
    private val closingSlugTag = Regex("""</[a-z0-9-]+>\s*$""")
    private val nameLine = Regex("""^name\s*:\s*\S+.*$""", RegexOption.IGNORE_CASE)
    private val descriptionLine = Regex("""^description\s*:\s*\S+.*$""", RegexOption.IGNORE_CASE)
    private val literalSkillOpening = Regex("""<skill(?:\s|>|/)""", RegexOption.IGNORE_CASE)
    private val literalSkillClosing = Regex("""</skill>""", RegexOption.IGNORE_CASE)

    fun isSyntheticSkillEnvelope(role: String?, content: String?): Boolean {
        if (role != "user") return false
        val text = content?.trim() ?: return false
        if (text.length < MIN_ENVELOPE_CHARS) return false

        return hasFrontmatterSignal(text) || hasArgumentsClosingSignal(text) || hasLiteralSkillSignal(text)
    }

    fun isSyntheticSkillEnvelope(message: LettaMessage): Boolean = when (message) {
        is UserMessage -> isSyntheticSkillEnvelope(role = "user", content = message.content)
        else -> false
    }

    private fun hasFrontmatterSignal(text: String): Boolean {
        val firstLines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .dropWhile { openingSlugTag.matches(it) }
            .take(FRONTMATTER_SCAN_LINES)
            .toList()
        return firstLines.any { nameLine.matches(it) } && firstLines.any { descriptionLine.matches(it) }
    }

    private fun hasArgumentsClosingSignal(text: String): Boolean {
        val tailLines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
            .takeLast(6)
        val closingIndex = tailLines.indexOfLast { closingSlugTag.containsMatchIn(it) }
        if (closingIndex < 0) return false
        return tailLines.take(closingIndex).any { it.startsWith("ARGUMENTS:", ignoreCase = true) }
    }

    private fun hasLiteralSkillSignal(text: String): Boolean =
        literalSkillOpening.containsMatchIn(text) || literalSkillClosing.containsMatchIn(text)
}
