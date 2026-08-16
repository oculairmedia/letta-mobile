package com.letta.mobile.data.chat.projection.fixture

import com.letta.mobile.data.a2ui.A2uiFrameEvent
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolApprovalDecision
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.ui.common.GroupPosition

/** Canonical inputs and expectations for the shared projection boundary. */
internal object ChatProjectionParityFixtures {
    val projectionCases: List<ChatProjectionFixture> = listOf(
        PlainConversationFixture.fixture,
        OptimisticAttachmentFixture.fixture,
        ToolApprovalFixture.fixture,
        ErrorFixture.fixture,
        ReasoningEchoFixture.fixture,
    )

    val transitionSequence: ProjectionTransitionSequence = StreamingTransitionFixture.sequence
}

internal data class ChatProjectionFixture(
    val id: String,
    val timelineEvents: List<TimelineEvent>,
    val expectedItems: List<RenderItemExpectation>,
    val expectedToolStates: List<ToolTimelineState> = emptyList(),
    val a2uiEvent: A2uiFrameEvent? = null,
) {
    val messages: List<UiMessage> = timelineEvents.mapNotNull(::timelineEventToUiMessage)
}

internal data class ProjectionTransitionSequence(
    val id: String,
    val frames: List<ProjectionFrame>,
)

internal data class ProjectionFrame(
    val timelineEvents: List<TimelineEvent>,
    val expectedChange: ChatMessageListChange,
) {
    val messages: List<UiMessage> = timelineEvents.mapNotNull(::timelineEventToUiMessage)
}

internal enum class RenderItemKind { Single, RunBlock, SkillEnvelope }

internal enum class MessageFeature {
    Pending,
    Reasoning,
    Error,
    ToolPending,
    ToolSucceeded,
    ApprovalPending,
    ApprovalApproved,
    Attachment,
}

internal data class RenderItemExpectation(
    val kind: RenderItemKind,
    val key: String,
    val runId: String?,
    val messages: List<MessageExpectation>,
)

internal data class MessageExpectation(
    val id: String,
    val groupPosition: GroupPosition = GroupPosition.None,
    val features: Set<MessageFeature> = emptySet(),
)

internal fun singleItem(
    key: String,
    message: MessageExpectation,
    runId: String? = null,
) = RenderItemExpectation(
    kind = RenderItemKind.Single,
    key = key,
    runId = runId,
    messages = listOf(message),
)

internal fun List<ChatRenderItem>.toFixtureExpectation(): List<RenderItemExpectation> = map { item ->
    when (item) {
        is ChatRenderItem.Single -> RenderItemExpectation(
            kind = RenderItemKind.Single,
            key = item.key,
            runId = item.stableRunId,
            messages = listOf(item.message.toFixtureExpectation(item.groupPosition)),
        )
        is ChatRenderItem.RunBlock -> RenderItemExpectation(
            kind = RenderItemKind.RunBlock,
            key = item.key,
            runId = item.runId,
            messages = item.messages.map { (message, position) ->
                message.toFixtureExpectation(position)
            },
        )
        is ChatRenderItem.SkillEnvelopeChip -> RenderItemExpectation(
            kind = RenderItemKind.SkillEnvelope,
            key = item.key,
            runId = null,
            messages = listOf(MessageExpectation(item.messageId)),
        )
    }
}

private fun UiMessage.toFixtureExpectation(groupPosition: GroupPosition) = MessageExpectation(
    id = id,
    groupPosition = groupPosition,
    features = listOfNotNull(
        MessageFeature.Pending.takeIf { isPending },
        MessageFeature.Reasoning.takeIf { isReasoning },
        MessageFeature.Error.takeIf { isError },
        MessageFeature.ApprovalPending.takeIf { approvalRequest != null },
        MessageFeature.Attachment.takeIf { attachments.isNotEmpty() },
        MessageFeature.ToolPending.takeIf { toolCalls.orEmpty().any { it.result == null } },
        MessageFeature.ToolSucceeded.takeIf { toolCalls.orEmpty().any { it.status == "success" } },
        MessageFeature.ApprovalApproved.takeIf {
            toolCalls.orEmpty().any { it.approvalDecision == UiToolApprovalDecision.Approved }
        },
    ).toSet(),
)
