package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Lucide
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.chat.projection.classifyToolCallState
import com.letta.mobile.data.chat.tools.ToolArguments
import com.letta.mobile.data.chat.tools.bundled.AgentToolPresenter
import com.letta.mobile.data.chat.tools.bundled.SkillToolPresenter
import com.letta.mobile.data.messaging.AgentMessageDeliveryState
import com.letta.mobile.data.messaging.AgentMessageDirection
import com.letta.mobile.data.messaging.agentMessageDisplayLabel
import com.letta.mobile.data.messaging.displayLabel
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.model.extractSubagentNotification
import com.letta.mobile.data.subagents.subagentIdentityOf
import com.letta.mobile.ui.markdown.SharedMarkdownText
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_tool_card_collapse_message
import com.letta.mobile.sharedui.resources.rows_tool_card_created_subagent
import com.letta.mobile.sharedui.resources.rows_tool_card_done
import com.letta.mobile.sharedui.resources.rows_tool_card_expand_message
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_agent_type
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_delivery
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_description
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_directory
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_failure
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_message_id
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_recipient
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_subagent_id
import com.letta.mobile.sharedui.resources.rows_tool_card_failed
import com.letta.mobile.sharedui.resources.rows_tool_card_loaded_skill
import com.letta.mobile.sharedui.resources.rows_tool_card_loading_skill
import com.letta.mobile.sharedui.resources.rows_tool_card_section_contents
import com.letta.mobile.sharedui.resources.rows_tool_card_section_prompt
import com.letta.mobile.sharedui.resources.rows_tool_card_sent_message_to
import com.letta.mobile.sharedui.resources.rows_tool_open_subagent
import com.letta.mobile.sharedui.resources.rows_tool_status_running
import com.letta.mobile.ui.chat.subagents.ExternalAgentBadge
import com.letta.mobile.ui.components.LettaToolCard
import com.letta.mobile.ui.components.LettaToolCardDivider
import com.letta.mobile.ui.components.LettaToolCardFact
import com.letta.mobile.ui.components.LettaToolCardFacts
import com.letta.mobile.ui.components.LettaToolCardHeader
import com.letta.mobile.ui.components.LettaToolCardParts
import com.letta.mobile.ui.components.LettaToolCardPreview
import com.letta.mobile.ui.components.LettaToolCardSection
import com.letta.mobile.ui.components.LettaToolCardStyle
import com.letta.mobile.ui.components.foundation.LettaMenuItem
import com.letta.mobile.ui.components.foundation.StatusGlyph
import com.letta.mobile.ui.components.line.DefaultDisclosureLabels
import com.letta.mobile.ui.components.line.LineDisclosure
import com.letta.mobile.ui.components.line.LineDisclosureLabels
import com.letta.mobile.ui.components.line.StatusTone
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * A tool call that is an event of its own draws as the shared [LettaToolCard] instead of a step line:
 * a message this agent sent another agent, a subagent it created, a skill it loaded. Each is only a
 * composition of the card's header, parts and body blocks; [detail] is the step's own disclosure, so
 * a card keeps its open state the way the step line did. Only for a call [isToolCallCard] accepts.
 */
@Composable
internal fun ToolCallCard(toolCall: UiToolCall, callbacks: ChatRowCallbacks, detail: LineDisclosure) {
    when {
        sendsAgentMessage(toolCall) -> SentMessageCard(toolCall, callbacks, detail)
        loadsSkill(toolCall) -> SkillLoadCard(toolCall, detail)
        else -> CreatedSubagentCard(toolCall, callbacks, detail)
    }
}

/** Whether [toolCall] draws as a [ToolCallCard]: a message this agent sent, a skill it loaded, or a subagent it created. */
@Composable
internal fun isToolCallCard(toolCall: UiToolCall): Boolean =
    sendsAgentMessage(toolCall) || loadsSkill(toolCall) || createsSubagent(toolCall)

private fun sendsAgentMessage(toolCall: UiToolCall): Boolean =
    toolCall.agentMessageProvenance?.direction == AgentMessageDirection.OUTBOUND

/** letta-code's Agent / Task tool, or any call the projection read a subagent dispatch from. */
@Composable
private fun createsSubagent(toolCall: UiToolCall): Boolean =
    toolCall.subagentDispatch != null || rememberToolStepLine(toolCall).presenterId == AgentToolPresenter.id

@Composable
private fun loadsSkill(toolCall: UiToolCall): Boolean = rememberToolStepLine(toolCall).presenterId == SkillToolPresenter.id

/** "Sent message to Scout · Detail", the message as one line its chevron opens; Detail shows the delivery. */
@Composable
private fun SentMessageCard(toolCall: UiToolCall, callbacks: ChatRowCallbacks, detail: LineDisclosure) {
    val provenance = toolCall.agentMessageProvenance ?: return
    val name = provenance.toAgentName ?: callbacks.resolveAgentName(provenance.toAgentId)
    val message = remember(toolCall.arguments) { toolCall.facts().string("body") }
    val failed = provenance.deliveryState == AgentMessageDeliveryState.FAILED
    val facts = listOfNotNull(
        LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_delivery), provenance.deliveryState.displayLabel()),
        LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_recipient), provenance.toAgentId, monospace = true),
        provenance.msgId.takeIf { it.isNotBlank() }
            ?.let { LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_message_id), it, monospace = true) },
        provenance.failureReason?.let { LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_failure), it) },
    )
    val failedLabel = stringResource(Res.string.rows_tool_card_failed)
    LettaToolCard(
        header = LettaToolCardHeader(
            label = stringResource(Res.string.rows_tool_card_sent_message_to),
            title = agentMessageDisplayLabel(provenance.toAgentId, name),
        ),
        modifier = Modifier.testTag(ChatRowTestTags.SENT_MESSAGE_CARD),
        parts = LettaToolCardParts(
            trailing = failureTrailing(failed, failedLabel),
            preview = message?.let { LettaToolCardPreview(it, MessageDisclosureLabels, ChatRowTestTags.SENT_MESSAGE_TEXT) },
            expansion = detail,
        ),
    ) { LettaToolCardFacts(facts) }
}

/**
 * "Created subagent · Running", over the task's title. The card opens the subagent in the page's
 * subagent view (matched by the call's id, then by the task's description); Detail shows its prompt.
 */
@Composable
private fun CreatedSubagentCard(toolCall: UiToolCall, callbacks: ChatRowCallbacks, detail: LineDisclosure) {
    val dispatch = toolCall.subagentDispatch
    val args = toolCall.facts()
    val title = dispatch?.description?.takeIf { it.isNotBlank() }
        ?: args.string("description")
        ?: args.string("prompt")?.let(ToolArguments::firstLine)
        ?: toolCall.name
    val prompt = dispatch?.prompt?.takeIf { it.isNotBlank() } ?: args.string("prompt")
    val facts = listOfNotNull(
        (dispatch?.subagentType ?: args.string("subagent_type"))
            ?.let { LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_agent_type), it) },
        dispatch?.subagentAgentId
            ?.let { LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_subagent_id), it, monospace = true) },
    )
    val callId = toolCall.toolCallId
    val openLabel = stringResource(Res.string.rows_tool_open_subagent)
    val open = callbacks.openSubagent?.takeIf { callId != null }
    LettaToolCard(
        header = LettaToolCardHeader(label = stringResource(Res.string.rows_tool_card_created_subagent), title = title, stacked = true),
        modifier = Modifier.testTag(ChatRowTestTags.CREATED_SUBAGENT_CARD),
        parts = LettaToolCardParts(
            trailing = { SubagentTrailing(toolCall) },
            expansion = detail,
            action = open?.let { opener ->
                LettaMenuItem(label = openLabel) {
                    opener(ChatSubagentTarget(callId.orEmpty(), title, dispatch?.subagentAgentId))
                }
            },
        ),
    ) {
        LettaToolCardFacts(facts)
        prompt?.let { text ->
            if (facts.isNotEmpty()) LettaToolCardDivider()
            LettaToolCardSection(stringResource(Res.string.rows_tool_card_section_prompt)) { CardMarkdown(text) }
        }
    }
}

/** An external coding agent's badge (Claude Code, Codex), as on the dispatch's line, then the status. */
@Composable
private fun RowScope.SubagentTrailing(toolCall: UiToolCall) {
    toolCall.subagentDispatch?.let { subagentIdentityOf(it.subagentType, listOf(it.subagentAgentId)) }?.let { ExternalAgentBadge(it) }
    SubagentStatus(toolCall)
}

/**
 * "Loaded skill pdf" as a quiet line whose chevron opens a panel: the skill's directory and
 * description, then its contents, rendered and scrolling in a capped height. "Loading skill" with
 * a spinner while the call runs.
 */
@Composable
private fun SkillLoadCard(toolCall: UiToolCall, detail: LineDisclosure) {
    val state = remember(toolCall) { classifyToolCallState(toolCall) }
    val running = state == ToolTimelineState.Running
    val result = toolCall.result.orEmpty()
    val body = remember(result) { skillBody(result) }
    val facts = listOfNotNull(
        remember(result) { skillDirectory(result) }
            ?.let { LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_directory), it, monospace = true) },
        body.first?.let(::skillDescription)
            ?.let { LettaToolCardFact(stringResource(Res.string.rows_tool_card_fact_description), it) },
    )
    val contents = body.second
    val contentsTitle = stringResource(Res.string.rows_tool_card_section_contents)
    val failedLabel = stringResource(Res.string.rows_tool_card_failed)
    LettaToolCard(
        header = LettaToolCardHeader(
            label = stringResource(if (running) Res.string.rows_tool_card_loading_skill else Res.string.rows_tool_card_loaded_skill),
            title = SKILL_KEYS.firstNotNullOfOrNull { toolCall.facts().string(it) },
            icon = Lucide.BookOpen,
            working = running,
        ),
        modifier = Modifier.testTag(ChatRowTestTags.SKILL_LOAD_CARD),
        parts = LettaToolCardParts(
            style = LettaToolCardStyle.Line,
            trailing = failureTrailing(state.isFailure(), failedLabel),
            expansion = detail,
        ),
        body = if (facts.isEmpty() && contents == null) null else ({
            LettaToolCardFacts(facts)
            contents?.let { text ->
                if (facts.isNotEmpty()) LettaToolCardDivider()
                LettaToolCardSection(contentsTitle) { CardMarkdown(text) }
            }
        }),
    )
}

/** A card's "Failed" status at its header's end when [failed]; nothing otherwise. */
private fun failureTrailing(failed: Boolean, label: String): (@Composable RowScope.() -> Unit)? =
    if (failed) ({ CardStatus(label, StatusTone.Failed) }) else null

/** Running while the call runs or its report says so; Done or Failed once its report says. Nothing else is guessed. */
@Composable
private fun SubagentStatus(toolCall: UiToolCall) {
    val state = remember(toolCall) { classifyToolCallState(toolCall) }
    val reported = remember(toolCall.result) { toolCall.result?.let(::extractSubagentNotification)?.status?.lowercase() }
    when {
        state == ToolTimelineState.Running || reported == "running" ->
            CardStatus(stringResource(Res.string.rows_tool_status_running), StatusTone.Working)
        state.isFailure() || reported in FAILED_WORDS -> CardStatus(stringResource(Res.string.rows_tool_card_failed), StatusTone.Failed)
        reported in DONE_WORDS -> CardStatus(stringResource(Res.string.rows_tool_card_done), StatusTone.Done)
    }
}

@Composable
private fun CardStatus(text: String, tone: StatusTone) {
    Row(
        modifier = Modifier.testTag(ChatRowTestTags.TOOL_CARD_STATUS),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusGlyph(tone)
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** A card section's markdown, in the timeline's renderer. */
@Composable
private fun CardMarkdown(text: String) {
    SharedMarkdownText(text = text, textColor = MaterialTheme.colorScheme.onSurface)
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

/** The frontmatter's `description:` value, unquoted. */
private fun skillDescription(frontmatter: String): String? =
    SKILL_DESCRIPTION.find(frontmatter)?.groupValues?.get(1)?.trim()?.trim('"', '\'')?.ifEmpty { null }

/** The message preview's chevron: "Show full message" / "Show message preview", spoken open / closed. */
private val MessageDisclosureLabels = LineDisclosureLabels(
    Res.string.rows_tool_card_expand_message,
    Res.string.rows_tool_card_collapse_message,
    DefaultDisclosureLabels.expandedState,
    DefaultDisclosureLabels.collapsedState,
)

private val SKILL_KEYS = listOf("skill", "command", "name")
private const val FENCE = "---"
private const val LAUNCHING_SKILL = "Launching skill"
private val SKILL_DIRECTORY = Regex("""(?im)^\s*(?:base directory(?: for this skill)?|skill directory|directory)\s*:\s*(.+)$""")
private val SKILL_DESCRIPTION = Regex("""(?im)^description\s*:\s*(.+)$""")
private val DONE_WORDS = setOf("completed", "complete", "success", "succeeded", "done")
private val FAILED_WORDS = setOf("failed", "error", "errored", "cancelled", "canceled", "killed")
