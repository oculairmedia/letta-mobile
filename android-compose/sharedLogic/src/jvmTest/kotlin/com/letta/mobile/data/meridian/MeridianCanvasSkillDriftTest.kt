package com.letta.mobile.data.meridian

import com.letta.mobile.data.canvas.compose.CanvasComposeColors
import com.letta.mobile.data.canvas.compose.CanvasComposeContract as Contract
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-jna0o.5: the `meridian-canvas` skill (scripts/deploy/skills) carries a compose quick
 * reference so the agent rarely needs `meridian guide compose`. Its numbers and colour names are
 * held to [CanvasComposeContract] here, so a cap change cannot leave the skill teaching the old one.
 * Its one-line description is what every turn pays for (`<available_skills>`), so its size is pinned.
 */
class MeridianCanvasSkillDriftTest {
    private val skill = File(SKILL).also { assertTrue(it.isFile, "missing ${it.absolutePath}") }.readText().replace("\r\n", "\n")

    @Test
    fun theQuickReferenceQuotesTheCurrentComposeCaps() {
        val expected = mapOf(
            "1 to ${Contract.MAX_ITEMS} in reading order" to "MAX_ITEMS",
            "up to ${Contract.MAX_TITLE_CHARS}" to "MAX_TITLE_CHARS",
            "markdown up to ${Contract.MAX_MARKDOWN_CHARS} characters" to "MAX_MARKDOWN_CHARS",
            "1 to ${Contract.MAX_CHECKLIST_ITEMS} entries of up to ${Contract.MAX_VALUE_CHARS} characters" to "MAX_CHECKLIST_ITEMS / MAX_VALUE_CHARS",
            "up to ${Contract.MAX_CARD_FIELDS} fields (label ${Contract.MAX_LABEL_CHARS}, value ${Contract.MAX_VALUE_CHARS})" to "card caps",
            "up to ${Contract.MAX_CARD_BODY_CHARS} characters of markdown" to "MAX_CARD_BODY_CHARS",
            "`TEXT {text, size: \"heading\"|\"body\"}`: up to ${Contract.MAX_TEXT_CHARS}" to "MAX_TEXT_CHARS",
            "at most ${maxLength(Contract.ARTIFACT_ID_PATTERN)}" to "ARTIFACT_ID_PATTERN",
            "at most ${maxLength(Contract.KEY_PATTERN)}, unique" to "KEY_PATTERN",
        )
        val stale = expected.filterKeys { it !in skill }.values
        assertTrue(stale.isEmpty(), "scripts/deploy/skills/meridian-canvas/SKILL.md is stale for: $stale")
        CanvasComposeColors.PRESETS.keys.forEach { assertTrue("`$it`" in skill, "colour $it missing from the skill") }
    }

    @Test
    fun theSkillLineStaysOneShortSentence() {
        val description = skill.lineSequence().first { it.startsWith("description: ") }.removePrefix("description: ")
        assertEquals("meridian-canvas", skill.lineSequence().first { it.startsWith("name: ") }.removePrefix("name: "))
        assertTrue(description.length <= MAX_DESCRIPTION_CHARS, "the per-turn skill line grew to ${description.length} chars")
    }

    private fun maxLength(pattern: String): Int = Regex("""\{0,(\d+)\}""").find(pattern)!!.groupValues[1].toInt() + 1

    private companion object {
        /** From the sharedLogic module directory, where Gradle runs its tests. */
        const val SKILL = "../../scripts/deploy/skills/meridian-canvas/SKILL.md"

        /** ~33 o200k tokens with the `- meridian-canvas:` prefix (design "The system-prompt pointer"). */
        const val MAX_DESCRIPTION_CHARS = 130
    }
}
