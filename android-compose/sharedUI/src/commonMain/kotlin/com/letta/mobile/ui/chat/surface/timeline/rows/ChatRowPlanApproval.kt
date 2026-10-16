package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.model.PlanApproval
import com.letta.mobile.data.model.PlanPreview
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_plan_feedback_placeholder
import com.letta.mobile.sharedui.resources.rows_plan_reject_title
import com.letta.mobile.sharedui.resources.rows_plan_title
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.markdown.SharedMarkdownText
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

// letta-mobile-8fq4j: ExitPlanMode's plan card, and the plan a write to a plans file proposes,
// for every host from this one renderer (the timeline row's approval slot and the Touch canvas's
// input tray both draw ApprovalRequestCard). Mirrors the official Letta desktop app (0.34.10):
// "Ready to code? Here is your plan:" over the rendered markdown, decided with Approve, or Reject
// with optional feedback for the agent. The reference offers no other choice (no "approve and
// switch permission mode"), so neither does this card.

private val PlanRejectCopy = RejectCopy(Res.string.rows_plan_reject_title, Res.string.rows_plan_feedback_placeholder)

/**
 * The plan card for a parked ExitPlanMode call: the plan, then Approve (closing the call with its
 * own arguments as `updated_input`, [PlanApproval.approveReason]) or Reject with optional
 * feedback. Returns false (renders nothing) when [approval] holds no ExitPlanMode call.
 */
@Composable
internal fun ExitPlanModeCard(approval: UiApprovalRequest, decider: ApprovalDecider): Boolean {
    val toolCall = approval.toolCalls.firstOrNull { PlanApproval.isExitPlanMode(it.name) } ?: return false
    val plan = remember(toolCall.arguments) { PlanApproval.preview(toolCall.name, toolCall.arguments) } ?: return false
    // Only the ExitPlanMode call's id: other bundled calls must not be decoded as the gate.
    val toolCallIds = remember(toolCall.toolCallId) { listOf(toolCall.toolCallId) }
    val approveReason = remember(toolCall.arguments) { PlanApproval.approveReason(toolCall.arguments) }
    ApprovalChrome(icon = LettaIcons.ListIcon, title = stringResource(Res.string.rows_plan_title)) {
        PlanPreviewBlock(plan)
        if (decider.submit != null) {
            ApprovalDecisionButtons(toolCallIds, decider, approveReason = approveReason, rejectCopy = PlanRejectCopy)
        }
    }
    return true
}

/**
 * The plan [toolCall] writes, when it writes to a plans file (the reference's `PlanWritePreview`);
 * nothing for any other call. ExitPlanMode's own card draws its plan, so it is skipped here.
 */
@Composable
internal fun PlanWritePreview(toolCall: UiApprovalToolCall) {
    if (PlanApproval.isExitPlanMode(toolCall.name)) return
    val plan = remember(toolCall.name, toolCall.arguments) { PlanApproval.preview(toolCall.name, toolCall.arguments) } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
        Text(
            text = stringResource(Res.string.rows_plan_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PlanPreviewBlock(plan)
    }
}

/** The plan's file (shortened) over its rendered markdown, in a bordered block. */
@Composable
private fun PlanPreviewBlock(plan: PlanPreview) {
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(ChatRowTestTags.APPROVAL_PLAN),
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(LettaDimens.Space.md),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            plan.shortFilePath?.let { path ->
                Text(
                    text = path,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            SharedMarkdownText(text = plan.content)
        }
    }
}
