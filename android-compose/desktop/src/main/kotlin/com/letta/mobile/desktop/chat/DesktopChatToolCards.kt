package com.letta.mobile.desktop.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.AskUserQuestion
import com.letta.mobile.data.model.AskUserQuestionItem
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalResponse
import com.letta.mobile.data.model.UiGeneratedComponent
import com.letta.mobile.data.model.UiToolCall

/**
 * Collapsible single-tool disclosure. Deliberately chrome-less when it succeeds:
 * a one-line activity row (glyph + name + summary + chevron) that sits in the
 * transcript flow rather than a boxed card, so a run of tool calls reads as a
 * quiet activity log instead of a stack of panels. Failures keep a card
 * treatment — an outlined, tinted surface — because they need to be noticed.
 */
@Composable
internal fun ToolCard(
    toolCall: UiToolCall,
    disclosureKey: String = toolCall.disclosureKey(),
) {
    var expanded by remember(disclosureKey) { mutableStateOf(toolCall.shouldInitiallyExpand()) }
    val isError = toolCall.isErrorStatus()
    val body: @Composable () -> Unit = {
        Column {
            ToolCardHeader(
                toolCall = toolCall,
                expanded = expanded,
                onToggle = { expanded = !expanded },
            )
            if (expanded) {
                ToolCardBody(toolCall = toolCall, isError = isError)
            }
        }
    }
    if (isError) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.7f)),
            content = body,
        )
    } else {
        Box(modifier = Modifier.fillMaxWidth()) { body() }
    }
}

@Composable
private fun ToolCardHeader(
    toolCall: UiToolCall,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val collapsedSummary = toolCall.stepLabel()
        .takeUnless { it == toolCall.name }
        ?: toolCall.stepSummary()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tool-card-toggle")
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Terminal,
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = toolCall.name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!expanded && collapsedSummary.isNotBlank()) {
            Text(
                text = collapsedSummary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        ToolFailureBadge(ToolStatusToken(toolCall.status ?: "tool call"))
        if (expanded) {
            Spacer(Modifier.weight(1f))
            // Only offered while open: a 36dp hit target in every collapsed row
            // would undo the compact activity-log rhythm.
            CopyIconButton(
                text = toolCall.copyPayload(),
                config = CopyActionConfig(contentDescription = "Copy tool call"),
            )
        }
        Icon(
            imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            contentDescription = if (expanded) "Collapse" else "Expand",
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ToolCardBody(toolCall: UiToolCall, isError: Boolean) {
    Column(
        modifier = Modifier
            .testTag("tool-card-body")
            .padding(start = 31.dp, end = 10.dp, top = 2.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        toolCall.arguments.takeIf { it.isNotBlank() }?.let { args ->
            SelectionContainer {
                Text(
                    text = "$ ${primaryToolArgument(ToolArgumentPayload(args))}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        toolCall.result?.takeIf { it.isNotBlank() }?.let { result ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Output",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                CopyIconButton(
                    text = result,
                    config = CopyActionConfig(contentDescription = "Copy output"),
                )
            }
            ToolOutputBlock(result, isError = isError)
        }
        DesktopImageAttachmentsGrid(
            attachments = toolCall.generatedImageAttachments,
            modifier = Modifier.fillMaxWidth(),
        )
        toolCall.executionTimeMs?.let { ms ->
            Text(
                text = "${toolCall.status?.replaceFirstChar { it.uppercase() } ?: "Done"} · $ms ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun GeneratedUiCard(generatedUi: UiGeneratedComponent) {
    ArtifactCard(
        icon = Icons.Outlined.Widgets,
        title = generatedUi.name,
        status = ToolStatusToken("A2UI"),
    ) {
        generatedUi.fallbackText?.takeIf { it.isNotBlank() }?.let { fallback ->
            Text(
                text = fallback,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = generatedUi.propsJson,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Threads the approval-decision callback (and the set of in-flight request ids)
 * from the chat controller down to the approval cards without widening every
 * intermediate composable's signature. Null when no interactive approval path is
 * wired (demo / HTTP-only gateways) — the cards then render read-only.
 *
 * `onDecision` mirrors the mobile chat contract
 * `(requestId, toolCallIds, approve, reason)`; an AskUserQuestion answer rides the
 * `reason` channel via [AskUserQuestion.encodeAnswerReason]. See letta-mobile-vilsn.8.
 */
internal data class DesktopApprovalDecisionHandler(
    val onDecision: (requestId: String, toolCallIds: List<String>, approve: Boolean, reason: String?) -> Unit,
    val submittingRequestIds: Set<String> = emptySet(),
)

internal val LocalDesktopApprovalDecision = staticCompositionLocalOf<DesktopApprovalDecisionHandler?> { null }

@Composable
internal fun ApprovalRequestCard(approvalRequest: UiApprovalRequest) {
    val handler = LocalDesktopApprovalDecision.current
    val isSubmitting = handler != null && approvalRequest.requestId in handler.submittingRequestIds
    // Structured AskUserQuestion answering takes precedence; falls through to the
    // generic disclosure when the parked call isn't an AskUserQuestion.
    if (DesktopAskUserQuestionCard(
            approval = approvalRequest,
            isSubmitting = isSubmitting,
            onDecision = handler?.onDecision,
        )
    ) {
        return
    }
    ArtifactCard(
        icon = Icons.Outlined.CheckCircle,
        title = "Approval requested",
        status = ToolStatusToken(approvalRequest.requestId),
    ) {
        approvalRequest.toolCalls.forEach { toolCall ->
            Text(
                text = "${toolCall.name} - ${toolCall.arguments}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * Desktop structured renderer for a parked `AskUserQuestion` tool call — parity
 * with the mobile `AskUserQuestionCard`. Shows each question with its options as
 * selectable chips (single- or multi-select) plus a free-text "Other" answer.
 * "Send answer" builds the `updated_input.answers` payload that closes the tool
 * call, riding the existing approval `onDecision` reason channel; "Dismiss"
 * denies the approval. See letta-mobile-vilsn.8.
 *
 * Returns false (renders nothing) when the approval is not an AskUserQuestion —
 * callers fall back to the generic approval card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DesktopAskUserQuestionCard(
    approval: UiApprovalRequest,
    isSubmitting: Boolean,
    onDecision: ((String, List<String>, Boolean, String?) -> Unit)?,
): Boolean {
    val toolCall = approval.toolCalls.firstOrNull { it.name == AskUserQuestion.ASK_USER_QUESTION_TOOL }
        ?: return false
    val spec = remember(toolCall.arguments) { AskUserQuestion.parse(toolCall.arguments) } ?: return false

    val toolCallIds = remember(approval) { approval.toolCalls.map { it.toolCallId } }

    // question text -> selected option labels
    val selections = remember(toolCall.arguments) { mutableStateMapOf<String, MutableList<String>>() }
    // question text -> free-text "Other" answer
    val otherText = remember(toolCall.arguments) { mutableStateMapOf<String, String>() }

    ArtifactCard(
        icon = Icons.Outlined.HelpOutline,
        title = "Question",
        status = ToolStatusToken(approval.requestId),
    ) {
        spec.questions.forEach { question ->
            DesktopAskUserQuestionBlock(
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
                },
                onOtherChanged = { otherText[question.question] = it },
            )
        }

        val answers = buildAskUserQuestionAnswers(spec.questions, selections, otherText)
        val canSubmit = answers.isNotEmpty() && answers.size == spec.questions.count { it.question.isNotBlank() }

        DesktopAskUserQuestionActions(
            requestId = approval.requestId,
            toolCallIds = toolCallIds,
            toolArguments = toolCall.arguments,
            answers = answers,
            isSubmitting = isSubmitting,
            canSubmit = canSubmit,
            onDecision = onDecision,
        )
    }
    return true
}

@Composable
private fun DesktopAskUserQuestionActions(
    requestId: String,
    toolCallIds: List<String>,
    toolArguments: String,
    answers: Map<String, List<String>>,
    isSubmitting: Boolean,
    canSubmit: Boolean,
    onDecision: ((String, List<String>, Boolean, String?) -> Unit)?,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onDecision?.invoke(requestId, toolCallIds, false, null) },
            enabled = !isSubmitting && onDecision != null,
        ) { Text("Dismiss") }
        Button(
            onClick = {
                val updatedInput = AskUserQuestion.buildUpdatedInput(toolArguments, answers)
                onDecision?.invoke(
                    requestId,
                    toolCallIds,
                    true,
                    AskUserQuestion.encodeAnswerReason(updatedInput),
                )
            },
            enabled = !isSubmitting && canSubmit && onDecision != null,
        ) { Text(if (isSubmitting) "Sending…" else "Send answer") }
    }
}

@Composable
private fun DesktopAskUserQuestionHeader(header: String?) {
    if (header.isNullOrBlank()) return
    Text(
        text = header,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopAskUserQuestionChipRow(
    options: List<com.letta.mobile.data.model.AskUserQuestionOption>,
    selected: List<String>,
    onToggleOption: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = option.label in selected,
                onClick = { onToggleOption(option.label) },
                label = { Text(option.label) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun DesktopAskUserQuestionOtherField(
    otherValue: String,
    onOtherChanged: (String) -> Unit,
) {
    OutlinedTextField(
        value = otherValue,
        onValueChange = onOtherChanged,
        label = { Text("Other") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DesktopAskUserQuestionBlock(
    question: AskUserQuestionItem,
    selected: List<String>,
    otherValue: String,
    onToggleOption: (String) -> Unit,
    onOtherChanged: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        DesktopAskUserQuestionHeader(question.header)
        Text(text = question.question, style = MaterialTheme.typography.bodySmall)
        DesktopAskUserQuestionChipRow(
            options = question.options,
            selected = selected,
            onToggleOption = onToggleOption,
        )
        DesktopAskUserQuestionOtherField(
            otherValue = otherValue,
            onOtherChanged = onOtherChanged,
        )
    }
}

/** Collect resolved answers: selected chip labels plus any free-text "Other" value. */
private fun buildAskUserQuestionAnswers(
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

@Composable
internal fun ApprovalResponseCard(approvalResponse: UiApprovalResponse) {
    val label = when (approvalResponse.approved) {
        true -> "Approved"
        false -> "Rejected"
        null -> "Approval response"
    }
    ArtifactCard(
        icon = if (approvalResponse.approved == false) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
        title = label,
        status = ToolStatusToken(approvalResponse.requestId ?: "response"),
    ) {
        approvalResponse.reason?.takeIf { it.isNotBlank() }?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (approvalResponse.approvals.isNotEmpty()) {
            Text(
                text = "${approvalResponse.approvals.size} tool decisions",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun ArtifactCard(
    icon: ImageVector?,
    title: String,
    status: ToolStatusToken,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ToolFailureBadge(status)
                Spacer(Modifier.weight(1f))
            }
            content()
        }
    }
}

/**
 * Tool cards are quiet on success; only failures need a persistent badge.
 */
@Composable
internal fun ToolFailureBadge(status: ToolStatusToken) {
    if (!status.isError()) return
    val color = MaterialTheme.colorScheme.error
    Surface(
        modifier = Modifier.testTag("tool-failure-badge"),
        shape = RoundedCornerShape(5.dp),
        color = Color.Transparent,
        contentColor = color,
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
    ) {
        Text(
            text = status.value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Monospace code block matching the mockup's #262626 inset. */
@Composable
internal fun CodeBlock(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}
