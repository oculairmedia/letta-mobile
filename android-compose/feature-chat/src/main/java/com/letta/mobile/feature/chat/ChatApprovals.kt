package com.letta.mobile.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.feature.chat.R
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.ui.components.TextInputDialog
import com.letta.mobile.ui.theme.chatTypography

@Composable
internal fun ApprovalRequestCard(
    approval: UiApprovalRequest,
    isSubmitting: Boolean,
    onDecision: ((String, List<String>, Boolean, String?) -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.screen_chat_approval_request_title),
            style = MaterialTheme.chatTypography.toolLabel,
        )
        Text(
            text = stringResource(R.string.screen_chat_approval_request_body),
            style = MaterialTheme.chatTypography.toolDetail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        approval.toolCalls.forEach { toolCall ->
            ToolCallCard(
                toolCall = UiToolCall(
                    name = toolCall.name,
                    arguments = toolCall.arguments,
                    result = null,
                    toolCallId = toolCall.toolCallId,
                ),
                approvalStateOverride = ToolApprovalState.RequestingInput,
                keepExpanded = true,
            )
        }
        ApprovalActionRow(
            approval = approval,
            isSubmitting = isSubmitting,
            onDecision = onDecision,
        )
    }
}

@Composable
internal fun ApprovalRequestControls(
    approval: UiApprovalRequest?,
    isSubmitting: Boolean,
    onDecision: ((String, List<String>, Boolean, String?) -> Unit)?,
) {
    var rememberedApproval by remember { mutableStateOf(approval) }
    LaunchedEffect(approval) {
        if (approval != null) rememberedApproval = approval
    }

    AnimatedVisibility(
        visible = approval != null && rememberedApproval != null,
        enter = ChatMotion.expandEnter(),
        exit = ChatMotion.expandExit(),
    ) {
        rememberedApproval?.let { visibleApproval ->
            Column(
                modifier = Modifier.padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.screen_chat_approval_request_body),
                    style = MaterialTheme.chatTypography.toolDetail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ApprovalActionRow(
                    approval = visibleApproval,
                    isSubmitting = isSubmitting,
                    onDecision = onDecision,
                )
            }
        }
    }
}

@Composable
internal fun ApprovalActionRow(
    approval: UiApprovalRequest,
    isSubmitting: Boolean,
    onDecision: ((String, List<String>, Boolean, String?) -> Unit)?,
) {
    var showRejectDialog by remember { mutableStateOf(false) }
    val toolCallIds = remember(approval) { approval.toolCalls.map { it.toolCallId } }

    TextInputDialog(
        show = showRejectDialog,
        title = stringResource(R.string.screen_chat_approval_reject_title),
        label = stringResource(R.string.screen_chat_approval_reason_label),
        confirmText = stringResource(R.string.screen_chat_approval_reject_action),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { reason ->
            showRejectDialog = false
            onDecision?.invoke(approval.requestId, toolCallIds, false, reason)
        },
        onDismiss = { showRejectDialog = false },
        placeholder = stringResource(R.string.screen_chat_approval_reason_placeholder),
        singleLine = false,
        minLines = 3,
        validate = { true },
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { showRejectDialog = true },
            enabled = !isSubmitting && onDecision != null,
        ) {
            Text(stringResource(R.string.screen_chat_approval_reject_action))
        }
        androidx.compose.material3.Button(
            onClick = {
                onDecision?.invoke(approval.requestId, toolCallIds, true, null)
            },
            enabled = !isSubmitting && onDecision != null,
        ) {
            Text(
                if (isSubmitting) stringResource(R.string.screen_chat_approval_submitting)
                else stringResource(R.string.screen_chat_approval_approve_action)
            )
        }
    }
}

@Composable
internal fun ApprovalResponseCard(message: UiMessage) {
    val approval = message.approvalResponse ?: return

    // Defensive: only render when an explicit decision was made. The mapper
    // already drops auto-approval echoes (Letta server emits approve=null in
    // bypassPermissions sessions), but if any other code path constructs a
    // UiApprovalResponse with all-null decisions we still must not paint it
    // as "Rejected" — that's how the long-standing mis-labeling bug surfaced.
    val explicitDecisions = listOfNotNull(approval.approved) +
        approval.approvals.mapNotNull { it.approved }
    if (explicitDecisions.isEmpty()) return

    val approved = explicitDecisions.any { it }
    val title = if (approved) {
        stringResource(R.string.screen_chat_approval_approved_title)
    } else {
        stringResource(R.string.screen_chat_approval_rejected_title)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = MaterialTheme.chatTypography.toolLabel)
        approval.reason?.takeIf { it.isNotBlank() }?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.chatTypography.toolDetail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
