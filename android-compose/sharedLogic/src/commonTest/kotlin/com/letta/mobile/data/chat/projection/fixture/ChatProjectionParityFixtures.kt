package com.letta.mobile.data.chat.projection.fixture

import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.chat.projection.ChatRenderItem
import com.letta.mobile.data.chat.projection.ToolTimelineState
import com.letta.mobile.data.chat.projection.timelineEventToUiMessage
import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolApprovalDecision
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.Role
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.parseTimelineInstant
import com.letta.mobile.ui.common.GroupPosition
import kotlinx.collections.immutable.persistentListOf

/**
 * Platform-neutral semantic fixtures for the shared chat projection contract.
 *
 * The expectations intentionally stop at [ChatRenderItem]. Android, desktop,
 * and Wasm hosts may compose those items differently, but must not reinterpret
 * their order, grouping, identity, or structured payload state.
 */
internal object ChatProjectionParityFixtures {
    val projectionCases: List<ChatProjectionFixture> = listOf(
        plainConversation(),
        optimisticAttachment(),
        toolApprovalRun(),
        errorMessage(),
    )

    val transitionSequence: ProjectionTransitionSequence = streamingTransitionSequence()

    private fun plainConversation() = ChatProjectionFixture(
        id = "plain-message-grouping",
        messages = listOf(
            message(id = "user-1", role = "user", content = "first"),
            message(id = "user-2", role = "user", content = "second", second = 1),
            message(id = "assistant-1", role = "assistant", content = "answer", second = 2),
        ),
        expectedItems = listOf(
            single("msg-assistant-1", expected("assistant-1")),
            single("msg-user-2", expected("user-2", GroupPosition.Last)),
            single("msg-user-1", expected("user-1", GroupPosition.First)),
        ),
    )

    private fun optimisticAttachment(): ChatProjectionFixture {
        val pendingImage = requireNotNull(
            timelineEventToUiMessage(
                TimelineEvent.Local(
                    position = 1.0,
                    otid = "image-otid",
                    content = "look at this",
                    role = Role.USER,
                    sentAt = parseTimelineInstant("2026-08-16T01:00:00Z"),
                    deliveryState = DeliveryState.SENDING,
                    attachments = persistentListOf(
                        MessageContentPart.Image(base64 = "image-bytes", mediaType = "image/png"),
                    ),
                ),
            ),
        )
        val response = message(
            id = "assistant-image",
            role = "assistant",
            content = "I can see it",
            second = 1,
            runId = "run-image",
        )
        return ChatProjectionFixture(
            id = "optimistic-image-adjacent-to-assistant-run",
            messages = listOf(pendingImage, response),
            expectedItems = listOf(
                single("run-image", expected("assistant-image"), runId = "run-image"),
                single(
                    key = "msg-image-otid",
                    message = expected(
                        id = "image-otid",
                        features = setOf(MessageFeature.Pending, MessageFeature.Attachment),
                    ),
                ),
            ),
        )
    }

    private fun toolApprovalRun(): ChatProjectionFixture {
        val pendingCall = UiToolCall(
            name = "Bash",
            arguments = "{\"command\":\"echo ready\"}",
            result = null,
            toolCallId = "call-pending",
        )
        val approvedCall = UiToolCall(
            name = "Read",
            arguments = "{\"path\":\"README.md\"}",
            result = "contents",
            status = "success",
            toolCallId = "call-approved",
            approvalDecision = UiToolApprovalDecision.Approved,
        )
        val messages = listOf(
            message(id = "user-tools", role = "user", content = "inspect it"),
            message(
                id = "reasoning-tools",
                role = "assistant",
                content = "I will inspect the repository",
                second = 1,
                runId = "run-tools",
                isReasoning = true,
            ),
            message(
                id = "tool-pending",
                role = "assistant",
                second = 2,
                runId = "run-tools",
                toolCalls = listOf(pendingCall),
                approvalRequest = UiApprovalRequest(
                    requestId = "approval-1",
                    toolCalls = listOf(
                        UiApprovalToolCall(
                            toolCallId = "call-pending",
                            name = "Bash",
                            arguments = pendingCall.arguments,
                        ),
                    ),
                ),
            ),
            message(
                id = "tool-approved",
                role = "assistant",
                second = 3,
                runId = "run-tools",
                toolCalls = listOf(approvedCall),
            ),
            message(
                id = "assistant-tools",
                role = "assistant",
                content = "Inspection complete",
                second = 4,
                runId = "run-tools",
            ),
        )
        return ChatProjectionFixture(
            id = "streaming-tool-run-with-approval-and-a2ui-link",
            messages = messages,
            expectedItems = listOf(
                RenderItemExpectation(
                    kind = RenderItemKind.RunBlock,
                    key = "run-tools",
                    runId = "run-tools",
                    messages = listOf(
                        expected(
                            "reasoning-tools",
                            groupPosition = GroupPosition.First,
                            features = setOf(MessageFeature.Reasoning),
                        ),
                        expected(
                            "tool-pending",
                            groupPosition = GroupPosition.Middle,
                            features = setOf(MessageFeature.ToolPending, MessageFeature.ApprovalPending),
                        ),
                        expected(
                            "tool-approved",
                            groupPosition = GroupPosition.Middle,
                            features = setOf(MessageFeature.ToolSucceeded, MessageFeature.ApprovalApproved),
                        ),
                        expected("assistant-tools", groupPosition = GroupPosition.Last),
                    ),
                ),
                single("msg-user-tools", expected("user-tools")),
            ),
            a2uiSurface = A2uiSurfaceState(
                surfaceId = "surface-tools",
                conversationId = "conversation-fixture",
                runId = "run-tools",
                approvalRequestId = "approval-1",
            ),
            expectedA2uiLink = A2uiLinkExpectation(
                surfaceId = "surface-tools",
                conversationId = "conversation-fixture",
                runId = "run-tools",
                approvalRequestId = "approval-1",
            ),
            expectedToolStates = listOf(
                ToolTimelineState.AwaitingApproval,
                ToolTimelineState.Succeeded,
            ),
        )
    }

    private fun errorMessage() = ChatProjectionFixture(
        id = "visible-terminal-error",
        messages = listOf(
            message(id = "user-error", role = "user", content = "try it"),
            message(
                id = "error-1",
                role = "assistant",
                content = "Model provider unavailable",
                second = 1,
                isError = true,
            ),
        ),
        expectedItems = listOf(
            single("msg-error-1", expected("error-1", features = setOf(MessageFeature.Error))),
            single("msg-user-error", expected("user-error")),
        ),
    )

    private fun streamingTransitionSequence(): ProjectionTransitionSequence {
        val user = message(id = "transition-user", role = "user", content = "hello")
        val pending = message(
            id = "transition-assistant",
            role = "assistant",
            content = "H",
            second = 1,
            runId = "run-transition",
            isPending = true,
        )
        val growing = pending.copy(content = "Hello")
        val settled = growing.copy(isPending = false)
        val rewrittenHistory = user.copy(content = "hello edited")
        return ProjectionTransitionSequence(
            id = "append-replace-and-full-rebuild",
            frames = listOf(
                ProjectionFrame(listOf(user), ChatMessageListChange.Full),
                ProjectionFrame(listOf(user, pending), ChatMessageListChange.AppendTail),
                ProjectionFrame(listOf(user, growing), ChatMessageListChange.ReplaceTail),
                ProjectionFrame(listOf(user, settled), ChatMessageListChange.ReplaceTail),
                ProjectionFrame(listOf(rewrittenHistory, settled), ChatMessageListChange.Full),
            ),
        )
    }

    private fun message(
        id: String,
        role: String,
        content: String = "",
        second: Int = 0,
        runId: String? = null,
        isPending: Boolean = false,
        isReasoning: Boolean = false,
        isError: Boolean = false,
        toolCalls: List<UiToolCall>? = null,
        approvalRequest: UiApprovalRequest? = null,
    ) = UiMessage(
        id = id,
        role = role,
        content = content,
        timestamp = "2026-08-16T01:00:${second.toString().padStart(2, '0')}Z",
        runId = runId,
        isPending = isPending,
        isReasoning = isReasoning,
        isError = isError,
        toolCalls = toolCalls,
        approvalRequest = approvalRequest,
    )

    private fun single(
        key: String,
        message: MessageExpectation,
        runId: String? = null,
    ) = RenderItemExpectation(
        kind = RenderItemKind.Single,
        key = key,
        runId = runId,
        messages = listOf(message),
    )

    private fun expected(
        id: String,
        groupPosition: GroupPosition = GroupPosition.None,
        features: Set<MessageFeature> = emptySet(),
    ) = MessageExpectation(id, groupPosition, features)
}

internal data class ChatProjectionFixture(
    val id: String,
    val messages: List<UiMessage>,
    val expectedItems: List<RenderItemExpectation>,
    val a2uiSurface: A2uiSurfaceState? = null,
    val expectedA2uiLink: A2uiLinkExpectation? = null,
    val expectedToolStates: List<ToolTimelineState> = emptyList(),
)

internal data class ProjectionTransitionSequence(
    val id: String,
    val frames: List<ProjectionFrame>,
)

internal data class ProjectionFrame(
    val messages: List<UiMessage>,
    val expectedChange: ChatMessageListChange,
)

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
    val groupPosition: GroupPosition,
    val features: Set<MessageFeature>,
)

internal data class A2uiLinkExpectation(
    val surfaceId: String,
    val conversationId: String,
    val runId: String,
    val approvalRequestId: String,
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
            messages = listOf(
                MessageExpectation(item.messageId, GroupPosition.None, emptySet()),
            ),
        )
    }
}

private fun UiMessage.toFixtureExpectation(groupPosition: GroupPosition): MessageExpectation {
    val features = buildSet {
        if (isPending) add(MessageFeature.Pending)
        if (isReasoning) add(MessageFeature.Reasoning)
        if (isError) add(MessageFeature.Error)
        if (approvalRequest != null) add(MessageFeature.ApprovalPending)
        if (attachments.isNotEmpty()) add(MessageFeature.Attachment)
        if (toolCalls.orEmpty().any { it.result == null }) add(MessageFeature.ToolPending)
        if (toolCalls.orEmpty().any { it.status == "success" }) add(MessageFeature.ToolSucceeded)
        if (toolCalls.orEmpty().any { it.approvalDecision == UiToolApprovalDecision.Approved }) {
            add(MessageFeature.ApprovalApproved)
        }
    }
    return MessageExpectation(id, groupPosition, features)
}
