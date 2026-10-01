package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.AskUserQuestionItem
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalResponse
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.runtime.RuntimeUserInputTools
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
import com.letta.mobile.sharedui.resources.rows_rejected
import com.letta.mobile.sharedui.resources.rows_send_answer
import com.letta.mobile.sharedui.resources.rows_sending
import com.letta.mobile.sharedui.resources.rows_tool_decisions
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// letta-mobile-bglj6.1: desktop's ApprovalRequestCard / DesktopAskUserQuestionCard /
// ApprovalResponseCard, with Android's approve / reject-with-reason controls
// (ChatApprovals.kt). Decisions go to ChatActions.submitApproval; desktop's
// LocalDesktopApprovalDecision is now the row's callbacks + capabilities.

/** A decision the card may raise, or null when the owner cannot take approvals. */
@Immutable
private class ApprovalDecider(
    val requestId: String,
    val isSubmitting: Boolean,
    val submit: ((toolCallIds: List<String>, approve: Boolean, reason: String?) -> Unit)?,
) {
    val enabled: Boolean get() = !isSubmitting && submit != null
}

@Composable
private fun rememberApprovalDecider(
    approval: UiApprovalRequest,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
): ApprovalDecider {
    val isSubmitting = context.itemState.activeApprovalRequestId == approval.requestId
    val enabled = context.capabilities.approvals
    return remember(approval.requestId, isSubmitting, enabled, callbacks) {
        ApprovalDecider(
            requestId = approval.requestId,
            isSubmitting = isSubmitting,
            submit = if (enabled) {
                { ids, approve, reason -> callbacks.actions.submitApproval(approval.requestId, ids, approve, reason) }
            } else {
                null
            },
        )
    }
}

@Composable
internal fun ApprovalRequestCard(
    approval: UiApprovalRequest,
    context: ChatRowContext,
    callbacks: ChatRowCallbacks,
) {
    val decider = rememberApprovalDecider(approval, context, callbacks)
    // A structured AskUserQuestion takes precedence over the generic disclosure.
    if (AskUserQuestionCard(approval, decider)) return
    ArtifactCard(icon = LettaIcons.CheckCircle, title = stringResource(Res.string.rows_approval_requested)) {
        approval.toolCalls.forEach { ApprovalToolCallLine(it) }
        // Only runtime user-input tools wait on the user (Android's requiresUserInput); every
        // other approval is resolved by the runtime, so its card stays read-only, as on desktop.
        if (decider.submit != null && approval.requiresUserInput()) {
            Text(
                text = stringResource(Res.string.rows_approval_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ApprovalActionRow(approval, decider)
        }
    }
}

private fun UiApprovalRequest.requiresUserInput(): Boolean =
    toolCalls.any { RuntimeUserInputTools.requiresUserInput(it.name) }

@Composable
private fun ApprovalToolCallLine(toolCall: UiApprovalToolCall) {
    Text(text = toolCall.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
    if (toolCall.arguments.isNotBlank()) {
        val primary = remember(toolCall.arguments) { primaryToolArgument(toolCall.arguments) }
        CodeBlock(primary)
    }
}

/** Approve, or reject with an optional reason typed inline (Android's reject dialog). */
@Composable
private fun ApprovalActionRow(approval: UiApprovalRequest, decider: ApprovalDecider) {
    val toolCallIds = remember(approval) { approval.toolCalls.map { it.toolCallId } }
    var rejecting by remember(approval.requestId) { mutableStateOf(false) }
    var reason by remember(approval.requestId) { mutableStateOf("") }
    if (rejecting) {
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it },
            label = { Text(stringResource(Res.string.rows_reject_reason)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.APPROVAL_REASON),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        if (rejecting) {
            OutlinedButton(onClick = { rejecting = false }) { Text(stringResource(Res.string.rows_cancel)) }
            Button(
                onClick = { decider.submit?.invoke(toolCallIds, false, reason.takeIf { it.isNotBlank() }) },
                enabled = decider.enabled,
            ) { Text(stringResource(Res.string.rows_reject)) }
        } else {
            OutlinedButton(onClick = { rejecting = true }, enabled = decider.enabled) {
                Text(stringResource(Res.string.rows_reject))
            }
            Button(onClick = { decider.submit?.invoke(toolCallIds, true, null) }, enabled = decider.enabled) {
                Text(stringResource(if (decider.isSubmitting) Res.string.rows_sending else Res.string.rows_approve))
            }
        }
    }
}

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
            val picked = selections[q.question].orEmpty().toMutableList()
            val other = otherText[q.question]?.takeIf { it.isNotBlank() }
            if (other != null && (q.multiSelect || picked.isEmpty())) picked.add(other)
            if (picked.isNotEmpty()) out[q.question] = picked
        }
        return out
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
    ArtifactCard(icon = LettaIcons.Help, title = stringResource(Res.string.rows_question)) {
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
    ArtifactCard(icon = icon, title = title) {
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
