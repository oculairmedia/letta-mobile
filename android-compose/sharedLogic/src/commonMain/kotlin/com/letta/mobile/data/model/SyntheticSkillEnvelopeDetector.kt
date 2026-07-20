package com.letta.mobile.data.model

object SyntheticSkillEnvelopeDetector {
    private const val MIN_ENVELOPE_CHARS = 200
    private const val FRONTMATTER_SCAN_LINES = 10
    private val openingSlugTag = Regex("""^<[a-z0-9-]+>\s*$""")
    private val closingSlugTag = Regex("""</[a-z0-9-]+>\s*$""")
    private val nameLine = Regex("""^name\s*:\s*\S+.*$""", RegexOption.IGNORE_CASE)
    private val descriptionLine = Regex("""^description\s*:\s*\S+.*$""", RegexOption.IGNORE_CASE)
    private val literalSkillOpening = Regex("""<skill(?:\s|[>/])""", RegexOption.IGNORE_CASE)
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

/**
 * Parsed components of a synthetic skill-instruction envelope.
 *
 * @property slug The skill identifier extracted from the opening/closing tags (e.g., "asus-router")
 * @property name The skill name from the frontmatter "name:" field
 * @property description The skill description from the frontmatter "description:" field
 * @property args The arguments string following "ARGUMENTS:" (empty string if not present)
 * @property rawContent The complete, unmodified envelope text
 */
data class SkillEnvelopeInfo(
    val slug: String,
    val name: String,
    val description: String,
    val args: String,
    val rawContent: String,
)

/**
 * Parse a synthetic skill-instruction envelope into structured components.
 *
 * Returns null if the content doesn't match the expected envelope structure:
 * opening tag, frontmatter (name/description), markdown content, optional
 * ARGUMENTS line, and matching closing tag.
 */
fun parseSkillEnvelope(content: String?): SkillEnvelopeInfo? {
    val text = content?.trim() ?: return null
    if (text.isEmpty()) return null

    // Extract opening tag slug: <slug>
    val openingMatch = Regex("""^<([a-z0-9-]+)>\s*$""", RegexOption.MULTILINE)
        .find(text) ?: return null
    val slug = openingMatch.groupValues[1]

    // Verify matching closing tag exists: </slug>
    val closingPattern = Regex("""</$slug>\s*$""", RegexOption.MULTILINE)
    if (!closingPattern.containsMatchIn(text)) return null

    // Extract frontmatter fields
    val lines = text.lines()
    var nameValue: String? = null
    var descriptionValue: String? = null

    // Scan first few lines after the opening tag for frontmatter
    val namePattern = Regex("""^name\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    val descPattern = Regex("""^description\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)

    for (line in lines.drop(1).take(10)) {
        val trimmedLine = line.trim()
        if (trimmedLine == "---") break

        namePattern.find(trimmedLine)?.let {
            nameValue = it.groupValues[1].trim()
        }
        descPattern.find(trimmedLine)?.let {
            descriptionValue = it.groupValues[1].trim()
        }

        if (nameValue != null && descriptionValue != null) break
    }

    if (nameValue == null || descriptionValue == null) return null

    // Extract ARGUMENTS value (optional)
    val argsPattern = Regex("""^ARGUMENTS:\s*(.*)$""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    val argsMatch = argsPattern.find(text)
    val args = argsMatch?.groupValues?.getOrNull(1)?.trim() ?: ""

    return SkillEnvelopeInfo(
        slug = slug,
        name = nameValue,
        description = descriptionValue,
        args = args,
        rawContent = text,
    )
}
