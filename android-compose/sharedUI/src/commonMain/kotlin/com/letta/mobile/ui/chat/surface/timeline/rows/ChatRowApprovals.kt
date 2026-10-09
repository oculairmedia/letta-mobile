package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Terminal
import com.letta.mobile.data.chat.projection.requiresUserInput
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.AskUserQuestionItem
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalResponse
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_approval_body
import com.letta.mobile.sharedui.resources.rows_approval_requested
import com.letta.mobile.sharedui.resources.rows_approval_response
import com.letta.mobile.sharedui.resources.rows_approve
import com.letta.mobile.sharedui.resources.rows_approved
import com.letta.mobile.sharedui.resources.rows_cancel
import com.letta.mobile.sharedui.resources.rows_dismiss
import com.letta.mobile.sharedui.resources.rows_option_description
import com.letta.mobile.sharedui.resources.rows_other
import com.letta.mobile.sharedui.resources.rows_question
import com.letta.mobile.sharedui.resources.rows_reject
import com.letta.mobile.sharedui.resources.rows_reject_reason
import com.letta.mobile.sharedui.resources.rows_reject_reason_placeholder
import com.letta.mobile.sharedui.resources.rows_reject_title
import com.letta.mobile.sharedui.resources.rows_rejected
import com.letta.mobile.sharedui.resources.rows_requesting_input
import com.letta.mobile.sharedui.resources.rows_send_answer
import com.letta.mobile.sharedui.resources.rows_sending
import com.letta.mobile.sharedui.resources.rows_tool_decisions
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.runtime.RuntimeUserInputTools
import com.letta.mobile.data.runtime.binding
import com.letta.mobile.ui.chat.session.ChatApprovalAnswer
import com.letta.mobile.ui.chat.surface.touchStyle
import com.letta.mobile.ui.haptics.LettaHapticCue
import com.letta.mobile.ui.haptics.LocalHaptics
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// letta-mobile-bglj6.1: desktop's ApprovalRequestCard / DesktopAskUserQuestionCard /
// ApprovalResponseCard, with Android's approve / reject-with-reason controls
// (ChatApprovals.kt). Decisions go to ChatActions.submitApproval; desktop's
// LocalDesktopApprovalDecision is now the row's callbacks + capabilities.
//
// letta-mobile-bglj6.1.22: on Touch the cards wear Android's chrome (no card surface, the
// requested calls as expanded tool cards with a "requesting input" chip), and rejecting asks for
// the reason in a modal dialog in every idiom, as Android's TextInputDialog did.

/**
 * A decision the card may raise, or null when the owner cannot take approvals. Wherever the card
 * is drawn (a timeline row, the Touch canvas's input tray) it decides through one of these.
 */
@Immutable
internal class ApprovalDecider(
    val requestId: String,
    val isSubmitting: Boolean,
    val submit: ((toolCallIds: List<String>, approve: Boolean, reason: String?) -> Unit)?,
    /** Approves and persists the server-offered rule [suggestionId] (letta-mobile-bzvro.11). */
    val submitAlwaysAllow: ((details: PendingApprovalDetails, suggestionId: String) -> Unit)? = null,
) {
    val enabled: Boolean get() = !isSubmitting && submit != null
}

/**
 * The decider for [approval]: submitting while it is the owner's [activeApprovalRequestId], and
 * deciding through [actions] only when the owner takes approvals ([approvalsEnabled]).
 */
@Composable
internal fun rememberApprovalDecider(
    approval: UiApprovalRequest,
    activeApprovalRequestId: String?,
    approvalsEnabled: Boolean,
    actions: ChatActions,
): ApprovalDecider {
    val isSubmitting = activeApprovalRequestId == approval.requestId
    return remember(approval.requestId, isSubmitting, approvalsEnabled, actions) {
        ApprovalDecider(
            requestId = approval.requestId,
            isSubmitting = isSubmitting,
            submit = if (approvalsEnabled) {
                { ids, approve, reason -> actions.submitApproval(ChatApprovalAnswer(approval.requestId, ids, approve, reason)) }
            } else {
                null
            },
            submitAlwaysAllow = if (approvalsEnabled) {
                { details, suggestionId ->
                    actions.submitApproval(
                        ChatApprovalAnswer(
                            requestId = approval.requestId,
                            toolCallIds = listOf(details.toolCallId),
                            approve = true,
                            reason = null,
                            selectedSuggestionIds = listOf(suggestionId),
                            suggestionBinding = details.binding,
                        ),
                    )
                }
            } else {
                null
            },
        )
    }
}

/**
 * A timeline row's approval card, drawn only while the request waits on the person
 * ([requiresUserInput]). letta-mobile-bglj6.1.25: a request the runtime resolves (Bash under
 * approve-all) stays on its message until the tool returns; it draws nothing here, so the row
 * reads as its plain tool line, as the legacy Android ApprovalRequestCard did.
 */
@Composable
internal fun ApprovalRequestCard(
    approval: UiApprovalRequest,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
) {
    if (!approval.requiresUserInput()) return
    val decider = rememberApprovalDecider(
        approval = approval,
        activeApprovalRequestId = context.itemState.activeApprovalRequestId,
        approvalsEnabled = context.capabilities.approvals,
        actions = callbacks.actions,
    )
    // The moment a decision lands on the person: one attention cue per request.
    val haptics = LocalHaptics.current
    LaunchedEffect(approval.requestId) {
        if (decider.submit != null) haptics.play(LettaHapticCue.ApprovalNeeded)
    }
    ApprovalRequestCard(approval, decider)
}

/**
 * The approval (or its structured AskUserQuestion) with its controls, deciding through [decider].
 * Callers pass only a request that waits on the person ([requiresUserInput]); the controls stay
 * disabled while the owner cannot take approvals.
 */
@Composable
internal fun ApprovalRequestCard(approval: UiApprovalRequest, decider: ApprovalDecider) {
    // A structured AskUserQuestion takes precedence over the generic disclosure, unless the request
    // parked now is another tool's (a bundled [Bash, AskUserQuestion]): that gate is answered first,
    // and the question's card takes over once it resolves.
    val parkedOtherTool = approval.details?.let { !RuntimeUserInputTools.requiresUserInput(it.toolName) } == true
    if (!parkedOtherTool && AskUserQuestionCard(approval, decider)) return
    val actionable = decider.submit != null
    ApprovalChrome(icon = LettaIcons.CheckCircle, title = stringResource(Res.string.rows_approval_requested)) {
        ApprovalCardContent(approval, decider, actionable)
    }
}

@Composable
private fun ColumnScope.ApprovalCardContent(approval: UiApprovalRequest, decider: ApprovalDecider, actionable: Boolean) {
    val touch = touchStyle()
    // Android leads with what it asks, then the calls; desktop lists the calls first.
    if (touch && actionable) ApprovalBody()
    approval.toolCalls.forEach { if (touch) ApprovalToolCallCard(it) else ApprovalToolCallLine(it) }
    approval.details?.let { ApprovalOfferedDetails(it) }
    if (actionable) {
        if (!touch) ApprovalBody()
        ApprovalActionRow(approval, decider)
    }
}

/**
 * The approval's container: desktop's [ArtifactCard]; on Touch Android's chrome, the title in the
 * tool label's voice over the content with no card around it (the timeline row is the surface).
 */
@Composable
private fun ApprovalChrome(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    if (!touchStyle()) {
        ArtifactCard(icon = icon, title = title, content = content)
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Text(text = title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun ApprovalBody() {
    Text(
        text = stringResource(Res.string.rows_approval_body),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ApprovalToolCallLine(toolCall: UiApprovalToolCall) {
    Text(text = toolCall.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    if (toolCall.arguments.isNotBlank()) {
        val primary = remember(toolCall.arguments) { primaryToolArgument(toolCall.arguments) }
        CodeBlock(primary)
    }
}

/**
 * Android's requested call (ToolCallCard with keepExpanded and the RequestingInput chip): the
 * tool's row, held open, with its primary argument under it. It has nothing to collapse, so it
 * takes no tap.
 */
@Composable
private fun ApprovalToolCallCard(toolCall: UiApprovalToolCall) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatRowTestTags.APPROVAL_TOOL_CALL)
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            Icon(
                imageVector = Lucide.Terminal,
                contentDescription = null,
                modifier = Modifier.size(LettaDimens.Control.iconSm),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = toolCall.name,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            RequestingInputChip()
        }
        if (toolCall.arguments.isNotBlank()) {
            Box(Modifier.padding(start = LettaDimens.Space.xxl)) { ToolArgumentLine(toolCall.arguments) }
        }
    }
}

/** Android's ToolApprovalChip in its RequestingInput state. */
@Composable
private fun RequestingInputChip() {
    Surface(
        modifier = Modifier.testTag(ChatRowTestTags.APPROVAL_REQUESTING_INPUT),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = REQUESTING_INPUT_CHIP_ALPHA),
    ) {
        Text(
            text = stringResource(Res.string.rows_requesting_input),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.hair),
        )
    }
}

/** Android's chip container alpha (ChatToolCallCards.ToolApprovalChip). */
private const val REQUESTING_INPUT_CHIP_ALPHA = 0.72f

/** Approve, or reject with an optional reason typed in a modal dialog (Android's TextInputDialog). */
@Composable
private fun ApprovalActionRow(approval: UiApprovalRequest, decider: ApprovalDecider) {
    val toolCallIds = remember(approval) { approval.toolCalls.map { it.toolCallId } }
    var rejecting by remember(approval.requestId) { mutableStateOf(false) }
    val haptics = LocalHaptics.current
    if (rejecting) {
        RejectReasonDialog(
            onReject = { reason ->
                haptics.play(LettaHapticCue.Reject)
                rejecting = false
                decider.submit?.invoke(toolCallIds, false, reason)
            },
            onDismiss = { rejecting = false },
        )
    }
    approval.details?.let { AlwaysAllowButtons(it, decider) }
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        OutlinedButton(
            onClick = {
                haptics.play(LettaHapticCue.ContextClick)
                rejecting = true
            },
            enabled = decider.enabled,
        ) {
            Text(stringResource(Res.string.rows_reject))
        }
        Button(
            onClick = {
                haptics.play(LettaHapticCue.Confirm)
                decider.submit?.invoke(toolCallIds, true, null)
            },
            enabled = decider.enabled,
        ) {
            Text(stringResource(if (decider.isSubmitting) Res.string.rows_sending else Res.string.rows_approve))
        }
    }
}

/**
 * The reject flow's focused modal (Android's TextInputDialog): a three-line reason, optional, so
 * Reject is always enabled; Cancel leaves the request pending. A blank reason rejects without one.
 */
@Composable
private fun RejectReasonDialog(onReject: (reason: String?) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ChatRowTestTags.APPROVAL_REJECT_DIALOG),
        title = { Text(stringResource(Res.string.rows_reject_title)) },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text(stringResource(Res.string.rows_reject_reason)) },
                placeholder = { Text(stringResource(Res.string.rows_reject_reason_placeholder)) },
                minLines = REJECT_REASON_MIN_LINES,
                modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.APPROVAL_REASON),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onReject(reason.takeIf { it.isNotBlank() }) },
                modifier = Modifier.testTag(ChatRowTestTags.APPROVAL_REJECT_CONFIRM),
            ) { Text(stringResource(Res.string.rows_reject)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.rows_cancel)) } },
    )
}

private const val REJECT_REASON_MIN_LINES = 3

/** The answers a user is composing for one AskUserQuestion request. */
@Immutable
private class QuestionAnswers(
    val selections: SnapshotStateMap<String, List<String>>,
    val otherText: SnapshotStateMap<String, String>,
) {
    fun toggle(question: AskUserQuestionItem, label: String) {
        val current = selections[question.question].orEmpty()
        selections[question.question] = if (question.multiSelect) {
            if (label in current) current - label else current + label
        } else {
            otherText[question.question] = ""
            listOf(label)
        }
    }

    /** Typing "Other" clears a single-select's chip: the two are mutually exclusive. */
    fun setOther(question: AskUserQuestionItem, text: String) {
        otherText[question.question] = text
        if (!question.multiSelect && text.isNotBlank()) selections[question.question] = emptyList()
    }

    fun build(questions: List<AskUserQuestionItem>): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (q in questions) {
            if (q.question.isBlank()) continue
            val picked = answerTo(q)
            if (picked.isNotEmpty()) out[q.question] = picked
        }
        return out
    }

    /** The picked chips, plus the "Other" text where it may join them (multi-select, or nothing picked). */
    private fun answerTo(q: AskUserQuestionItem): List<String> {
        val picked = selections[q.question].orEmpty().toMutableList()
        val other = otherText[q.question]?.takeIf { it.isNotBlank() } ?: return picked
        if (q.multiSelect || picked.isEmpty()) picked.add(other)
        return picked
    }
}

/**
 * Structured renderer for a parked AskUserQuestion: option chips (single or multi select)
 * plus a free-text "Other". "Send answer" closes the tool call through the approval reason
 * channel; "Dismiss" denies it. Returns false (renders nothing) for any other approval.
 */
@Composable
private fun AskUserQuestionCard(approval: UiApprovalRequest, decider: ApprovalDecider): Boolean {
    val toolCall = approval.toolCalls.firstOrNull { it.name == AskUserQuestion.ASK_USER_QUESTION_TOOL }
        ?: return false
    val spec = remember(toolCall.arguments) { AskUserQuestion.parse(toolCall.arguments) } ?: return false
    // Only the AskUserQuestion call's id: other bundled calls must not be decoded as the gate.
    val toolCallIds = remember(toolCall.toolCallId) { listOf(toolCall.toolCallId) }
    val stateKey = "${approval.requestId}:${toolCall.toolCallId}:${toolCall.arguments}"
    val answers = remember(stateKey) { QuestionAnswers(mutableStateMapOf(), mutableStateMapOf()) }
    ApprovalChrome(icon = LettaIcons.Help, title = stringResource(Res.string.rows_question)) {
        spec.questions.forEach { QuestionBlock(it, answers) }
        val built = answers.build(spec.questions)
        val canSubmit = built.isNotEmpty() && built.size == spec.questions.count { it.question.isNotBlank() }
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
            OutlinedButton(
                onClick = { decider.submit?.invoke(toolCallIds, false, null) },
                enabled = decider.enabled,
            ) { Text(stringResource(Res.string.rows_dismiss)) }
            Button(
                onClick = {
                    val updated = AskUserQuestion.buildUpdatedInput(toolCall.arguments, built)
                    decider.submit?.invoke(toolCallIds, true, AskUserQuestion.encodeAnswerReason(updated))
                },
                enabled = decider.enabled && canSubmit,
            ) {
                Text(stringResource(if (decider.isSubmitting) Res.string.rows_sending else Res.string.rows_send_answer))
            }
        }
    }
    return true
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionBlock(question: AskUserQuestionItem, answers: QuestionAnswers) {
    val selected = answers.selections[question.question].orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm), modifier = Modifier.fillMaxWidth()) {
        question.header?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
        Text(text = question.question, style = MaterialTheme.typography.bodySmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
        ) {
            question.options.forEach { option ->
                FilterChip(
                    selected = option.label in selected,
                    onClick = { answers.toggle(question, option.label) },
                    label = { Text(option.label) },
                )
            }
        }
        question.options.forEach { option ->
            option.description?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = stringResource(Res.string.rows_option_description, option.label, description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = answers.otherText[question.question].orEmpty(),
            onValueChange = { answers.setOther(question, it) },
            label = { Text(stringResource(Res.string.rows_other)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = LettaDimens.Space.hair),
        )
    }
}

@Composable
internal fun ApprovalResponseCard(response: UiApprovalResponse) {
    // The shared verdict fails closed: any explicit rejection reads Rejected.
    val (title, icon) = when (response.verdict) {
        true -> stringResource(Res.string.rows_approved) to LettaIcons.CheckCircle
        false -> stringResource(Res.string.rows_rejected) to LettaIcons.Error
        null -> stringResource(Res.string.rows_approval_response) to LettaIcons.CheckCircle
    }
    ApprovalChrome(icon = icon, title = title) {
        response.reason?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
        val decisions = response.approvals.size
        if (decisions > 0) {
            Text(
                text = pluralStringResource(Res.plurals.rows_tool_decisions, decisions, decisions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
