package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.AskUserQuestionItem
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.theme.chatTypography

/**
 * Structured renderer for a parked `AskUserQuestion` tool call. Shows each
 * question with its options as selectable chips (single- or multi-select),
 * plus a free-text "Other" answer. Submitting builds the `updated_input.answers`
 * payload that closes the tool call. See epic letta-mobile-vilsn.
 *
 * Returns false (renders nothing) when the approval is not an AskUserQuestion —
 * callers fall back to the generic approval card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskUserQuestionCard(
    approval: UiApprovalRequest,
    isSubmitting: Boolean,
    onDecision: ((String, List<String>, Boolean, String?) -> Unit)?,
): Boolean {
    val toolCall = approval.toolCalls.firstOrNull { it.name == AskUserQuestion.ASK_USER_QUESTION_TOOL }
        ?: return false
    val spec = remember(toolCall.arguments) { AskUserQuestion.parse(toolCall.arguments) } ?: return false

    val toolCallIds = remember(approval) { approval.toolCalls.map { it.toolCallId } }
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    // question text -> selected option labels
    val selections = remember(toolCall.arguments) { mutableStateMapOf<String, MutableList<String>>() }
    // question text -> free-text "Other" answer
    val otherText = remember(toolCall.arguments) { mutableStateMapOf<String, String>() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.screen_chat_ask_user_question_title),
            style = MaterialTheme.chatTypography.toolLabel,
        )

        spec.questions.forEach { question ->
            AskUserQuestionBlock(
                question = question,
                selected = selections[question.question].orEmpty(),
                otherValue = otherText[question.question].orEmpty(),
                onToggleOption = { label ->
                    val current = selections.getOrPut(question.question) { mutableListOf() }
                    if (question.multiSelect) {
                        if (!current.remove(label)) current.add(label)
                    } else {
                        current.clear()
                        current.add(label)
                    }
                    // trigger recomposition (SnapshotStateMap tracks value identity)
                    selections[question.question] = current.toMutableList()
                    HapticEffects.contextClick(haptic, view)
                },
                onOtherChanged = { otherText[question.question] = it },
            )
        }

        val answers = buildAnswers(spec.questions, selections, otherText)
        val canSubmit = answers.isNotEmpty() && answers.size == spec.questions.count { it.question.isNotBlank() }

        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    HapticEffects.reject(haptic, view)
                    onDecision?.invoke(approval.requestId, toolCallIds, false, null)
                },
                enabled = !isSubmitting && onDecision != null,
            ) { Text(stringResource(R.string.screen_chat_ask_user_question_dismiss)) }
            Button(
                onClick = {
                    HapticEffects.confirm(haptic, view)
                    val updatedInput = AskUserQuestion.buildUpdatedInput(toolCall.arguments, answers)
                    onDecision?.invoke(
                        approval.requestId,
                        toolCallIds,
                        true,
                        AskUserQuestion.encodeAnswerReason(updatedInput),
                    )
                },
                enabled = !isSubmitting && canSubmit && onDecision != null,
            ) {
                Text(
                    if (isSubmitting) stringResource(R.string.screen_chat_approval_submitting)
                    else stringResource(R.string.screen_chat_ask_user_question_send),
                )
            }
        }
    }
    return true
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskUserQuestionBlock(
    question: AskUserQuestionItem,
    selected: List<String>,
    otherValue: String,
    onToggleOption: (String) -> Unit,
    onOtherChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        question.header?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, style = MaterialTheme.chatTypography.toolLabel, fontWeight = FontWeight.SemiBold)
        }
        Text(text = question.question, style = MaterialTheme.chatTypography.toolDetail)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            question.options.forEach { option ->
                val isSelected = option.label in selected
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggleOption(option.label) },
                    label = { Text(option.label) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }
        OutlinedTextField(
            value = otherValue,
            onValueChange = onOtherChanged,
            label = { Text(stringResource(R.string.screen_chat_ask_user_question_other)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

/** Collect resolved answers: selected chip labels plus any free-text "Other" value. */
private fun buildAnswers(
    questions: List<AskUserQuestionItem>,
    selections: Map<String, List<String>>,
    otherText: Map<String, String>,
): Map<String, List<String>> {
    val out = LinkedHashMap<String, List<String>>()
    for (q in questions) {
        if (q.question.isBlank()) continue
        val picked = selections[q.question].orEmpty().toMutableList()
        otherText[q.question]?.takeIf { it.isNotBlank() }?.let { picked.add(it) }
        if (picked.isNotEmpty()) out[q.question] = picked
    }
    return out
}
