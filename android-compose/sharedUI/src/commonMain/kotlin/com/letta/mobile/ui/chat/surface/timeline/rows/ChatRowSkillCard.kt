package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Lucide
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.chat.projection.classifyToolCallState
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_description
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_directory
import com.letta.mobile.sharedui.resources.rows_tool_card_failed
import com.letta.mobile.sharedui.resources.rows_tool_card_loaded_skill
import com.letta.mobile.sharedui.resources.rows_tool_card_loading_skill
import com.letta.mobile.sharedui.resources.rows_tool_card_section_contents
import com.letta.mobile.sharedui.resources.rows_tool_card_section_instructions
import com.letta.mobile.ui.components.LettaToolCard
import com.letta.mobile.ui.components.LettaToolCardDivider
import com.letta.mobile.ui.components.LettaToolCardFact
import com.letta.mobile.ui.components.LettaToolCardFacts
import com.letta.mobile.ui.components.LettaToolCardHeader
import com.letta.mobile.ui.components.LettaToolCardParts
import com.letta.mobile.ui.components.LettaToolCardSection
import com.letta.mobile.ui.components.LettaToolCardStyle
import com.letta.mobile.ui.components.line.LineDisclosure
import org.jetbrains.compose.resources.stringResource

/**
 * By tool call id, the instructions a loaded skill brought in that were too large to hold inline:
 * a pager over their stored body, which the settled timeline provides. Null for any other call.
 */
internal val LocalSkillInstructionPages = compositionLocalOf<(String) -> (@Composable () -> Unit)?> { { null } }

/**
 * "Loaded skill pdf" as a quiet line whose chevron opens a panel: the skill's directory and
 * description, then its contents, rendered and scrolling in a capped height. "Loading skill" with
 * a spinner while the call runs. The contents are the skill's instructions when the runtime sent
 * them apart from the result (which then only says "Launching skill: pdf"); instructions too large
 * to hold inline open on demand, a page at a time, under the same chevron.
 */
@Composable
internal fun SkillLoadCard(toolCall: UiToolCall, detail: LineDisclosure) {
    val state = remember(toolCall) { classifyToolCallState(toolCall) }
    val running = state == ToolTimelineState.Running
    val result = toolCall.result.orEmpty()
    val instructions = toolCall.skillInstructions
    val body = remember(result, instructions) { skillContents(toolCall) }
    val pages = toolCall.toolCallId?.let(LocalSkillInstructionPages.current)
    val contentsTitle = stringResource(Res.string.rows_tool_card_section_contents)
    val instructionsTitle = stringResource(Res.string.rows_tool_card_section_instructions)
    val sections = listOfNotNull(
        body.second?.let { text -> @Composable { LettaToolCardSection(contentsTitle) { CardMarkdown(text) } } },
        pages?.let { stored -> @Composable { LettaToolCardSection(instructionsTitle) { stored() } } },
    )
    LettaToolCard(
        header = LettaToolCardHeader(
            label = stringResource(if (running) Res.string.rows_tool_card_loading_skill else Res.string.rows_tool_card_loaded_skill),
            title = SKILL_KEYS.firstNotNullOfOrNull { toolCall.facts().string(it) } ?: skillNameIn(result),
            icon = Lucide.BookOpen,
            working = running,
        ),
        modifier = Modifier.testTag(ChatRowTestTags.SKILL_LOAD_CARD),
        parts = LettaToolCardParts(
            style = LettaToolCardStyle.Line,
            trailing = failureTrailing(state.isFailure(), stringResource(Res.string.rows_tool_card_failed)),
            expansion = detail,
        ),
        body = cardBody(skillFacts(toolCall, body), sections),
    )
}

/** A skill's directory (from its result) and description (from the frontmatter of its [contents]), where they are known. */
@Composable
private fun skillFacts(toolCall: UiToolCall, contents: Pair<String?, String?>): List<LettaToolCardFact> = listOfNotNull(
    remember(toolCall.result) { toolCall.result?.let(::skillDirectory) }
        ?.let { LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_directory), it, monospace = true) },
    contents.first?.let(::skillDescription)
        ?.let { LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_description), it) },
)

/** A card's facts, then its sections with a divider before each one that follows anything; null when it has neither. */
private fun cardBody(facts: List<LettaToolCardFact>, sections: List<@Composable () -> Unit>): (@Composable ColumnScope.() -> Unit)? {
    if (facts.isEmpty() && sections.isEmpty()) return null
    return {
        LettaToolCardFacts(facts)
        sections.forEachIndexed { index, section ->
            if (index > 0 || facts.isNotEmpty()) LettaToolCardDivider()
            section()
        }
    }
}

/** The skill's name from its result when the call's arguments do not name it: "Launching skill: pdf", else its directory's name. */
private fun skillNameIn(result: String): String? {
    LAUNCHING_NAME.find(result)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return skillDirectory(result)?.trimEnd('/', '\\')?.substringAfterLast('/')?.substringAfterLast('\\')?.ifEmpty { null }
}

/** The skill's directory, from its result's "Base directory for this skill: …" (or "Directory: …") line. */
private fun skillDirectory(result: String): String? =
    SKILL_DIRECTORY.find(result)?.groupValues?.get(1)?.trim()?.ifEmpty { null }

/**
 * The result's frontmatter block (between `---` fences at its top) and its markdown contents,
 * without the directory line or the "Launching skill" line; (null, contents) when it has no
 * frontmatter, and null contents when nothing is left.
 */
private fun skillBody(result: String): Pair<String?, String?> {
    val body = result.lineSequence()
        .filterNot { SKILL_DIRECTORY.containsMatchIn(it) || it.trimStart().startsWith(LAUNCHING_SKILL, ignoreCase = true) }
        .joinToString("\n")
        .trim()
    if (!body.startsWith(FENCE)) return null to body.ifBlank { null }
    val end = body.indexOf("\n$FENCE", startIndex = FENCE.length)
    if (end < 0) return null to body
    return body.substring(FENCE.length, end) to body.substring(end + FENCE.length + 1).trim().ifBlank { null }
}

/**
 * The skill's frontmatter and contents ([skillBody]): from its instructions when the runtime sent
 * them apart from the result, without their wrapping tags (`<skill_content name="pdf">` ...
 * `</skill_content>`, or a bare slug tag); from its result otherwise.
 */
private fun skillContents(toolCall: UiToolCall): Pair<String?, String?> {
    val lines = toolCall.skillInstructions?.trim()?.lines() ?: return skillBody(toolCall.result.orEmpty())
    val opens = lines.firstOrNull()?.trim()?.let(ENVELOPE_OPENING::matches) == true
    val closes = lines.size > 1 && lines.last().trim().let(ENVELOPE_CLOSING::matches)
    return skillBody(lines.subList(if (opens) 1 else 0, if (closes) lines.size - 1 else lines.size).joinToString("\n"))
}

/** The frontmatter's `description:` value, unquoted. */
private fun skillDescription(frontmatter: String): String? =
    SKILL_DESCRIPTION.find(frontmatter)?.groupValues?.get(1)?.trim()?.trim('"', '\'')?.ifEmpty { null }


private val SKILL_KEYS = listOf("skill", "command", "name", "skill_name", "skillName")
private val LAUNCHING_NAME = Regex("""Launching skill:\s*(\S+)""", RegexOption.IGNORE_CASE)
private const val FENCE = "---"
private const val LAUNCHING_SKILL = "Launching skill"
// Flags as options, not an inline (?im) prefix: JavaScript RegExp (web/wasm) rejects inline flags.
private val SKILL_LINE_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
private val SKILL_DIRECTORY = Regex(
    """^\s*(?:base directory(?: for this skill)?|skill directory|directory)\s*:\s*(.+)$""",
    SKILL_LINE_OPTIONS,
)
private val ENVELOPE_OPENING = Regex("""^<[a-z0-9_-]+(?:\s[^>]*)?>$""", RegexOption.IGNORE_CASE)
private val ENVELOPE_CLOSING = Regex("""^</[a-z0-9_-]+>$""", RegexOption.IGNORE_CASE)
private val SKILL_DESCRIPTION = Regex("""^description\s*:\s*(.+)$""", SKILL_LINE_OPTIONS)
