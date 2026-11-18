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
import com.composables.icons.lucide.Lucide
import com.letta.mobile.sharedui.resources.rows_tool_card_section_message
import com.letta.mobile.ui.components.LettaToolCardAction
import com.composables.icons.lucide.GitBranch
import com.composables.icons.lucide.Send
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
import com.letta.mobile.sharedui.resources.rows_tool_card_created_subagent
import com.letta.mobile.sharedui.resources.rows_tool_card_done
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_agent_type
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_delivery
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_failure
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_message_id
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_recipient
import com.letta.mobile.sharedui.resources.rows_tool_card_fact_subagent_id
import com.letta.mobile.sharedui.resources.rows_tool_card_failed
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
import com.letta.mobile.ui.components.LettaToolCardSection
import com.letta.mobile.ui.components.LettaToolCardStyle
import com.letta.mobile.ui.components.foundation.LettaMenuItem
import com.letta.mobile.ui.components.foundation.StatusGlyph
import com.letta.mobile.ui.components.line.LineDisclosure
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
    val messageTitle = stringResource(Res.string.rows_tool_card_section_message)
    LettaToolCard(
        header = LettaToolCardHeader(
            label = stringResource(Res.string.rows_tool_card_sent_message_to),
            title = agentMessageDisplayLabel(provenance.toAgentId, name),
            icon = Lucide.Send,
        ),
        modifier = Modifier.testTag(ChatRowTestTags.SENT_MESSAGE_CARD),
        parts = LettaToolCardParts(style = LettaToolCardStyle.Line, trailing = failureTrailing(failed, failedLabel), expansion = detail),
    ) {
        message?.let { text ->
            LettaToolCardSection(messageTitle) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.testTag(ChatRowTestTags.SENT_MESSAGE_TEXT),
                )
            }
            LettaToolCardDivider()
        }
        LettaToolCardFacts(facts)
    }
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
        header = LettaToolCardHeader(
            label = stringResource(Res.string.rows_tool_card_created_subagent),
            title = title,
            icon = Lucide.GitBranch,
        ),
        modifier = Modifier.testTag(ChatRowTestTags.CREATED_SUBAGENT_CARD),
        parts = LettaToolCardParts(style = LettaToolCardStyle.Line, trailing = { SubagentTrailing(toolCall) }, expansion = detail),
    ) {
        open?.let { opener ->
            LettaToolCardAction(
                LettaMenuItem(label = openLabel, tag = ChatRowTestTags.CREATED_SUBAGENT_OPEN) {
                    opener(ChatSubagentTarget(callId.orEmpty(), title, dispatch?.subagentAgentId))
                },
            )
        }
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

/** A card's "Failed" status at its header's end when [failed]; nothing otherwise. */
internal fun failureTrailing(failed: Boolean, label: String): (@Composable RowScope.() -> Unit)? =
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
internal fun CardMarkdown(text: String) {
    SharedMarkdownText(text = text, textColor = MaterialTheme.colorScheme.onSurface)
}

private val DONE_WORDS = setOf("completed", "complete", "success", "succeeded", "done")
private val FAILED_WORDS = setOf("failed", "error", "errored", "cancelled", "canceled", "killed")
