package com.letta.mobile.data.chat.projection.fixture

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.Role
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.parseTimelineInstant
import com.letta.mobile.ui.common.GroupPosition
import kotlinx.collections.immutable.persistentListOf

private val fixtureInstant = parseTimelineInstant("2026-08-16T01:00:00Z")

internal object PlainConversationFixture {
    val fixture = ChatProjectionFixture(
        id = "plain-message-grouping",
        timelineEvents = listOf(
            TimelineEvent.Confirmed(
                position = 0.0,
                otid = "otid-user-1",
                content = "first",
                serverId = "user-1",
                messageType = TimelineMessageType.USER,
                date = fixtureInstant,
                runId = null,
                stepId = null,
            ),
            TimelineEvent.Confirmed(
                position = 1.0,
                otid = "otid-user-2",
                content = "second",
                serverId = "user-2",
                messageType = TimelineMessageType.USER,
                date = fixtureInstant,
                runId = null,
                stepId = null,
            ),
            TimelineEvent.Confirmed(
                position = 2.0,
                otid = "otid-assistant-1",
                content = "answer",
                serverId = "assistant-1",
                messageType = TimelineMessageType.ASSISTANT,
                date = fixtureInstant,
                runId = null,
                stepId = null,
            ),
        ),
        expectedItems = listOf(
            singleItem("msg-otid-assistant-1", MessageExpectation("assistant-1")),
            singleItem("msg-otid-user-2", MessageExpectation("user-2", GroupPosition.Last)),
            singleItem("msg-otid-user-1", MessageExpectation("user-1", GroupPosition.First)),
        ),
    )
}

internal object OptimisticAttachmentFixture {
    val fixture = ChatProjectionFixture(
        id = "optimistic-image-adjacent-to-assistant-run",
        timelineEvents = listOf(
            TimelineEvent.Local(
                position = 0.0,
                otid = "image-otid",
                content = "look at this",
                role = Role.USER,
                sentAt = fixtureInstant,
                deliveryState = DeliveryState.SENDING,
                attachments = persistentListOf(
                    MessageContentPart.Image(base64 = "image-bytes", mediaType = "image/png"),
                ),
            ),
            TimelineEvent.Confirmed(
                position = 1.0,
                otid = "otid-assistant-image",
                content = "I can see it",
                serverId = "assistant-image",
                messageType = TimelineMessageType.ASSISTANT,
                date = fixtureInstant,
                runId = "run-image",
                stepId = "step-image",
            ),
        ),
        expectedItems = listOf(
            singleItem("run-image", MessageExpectation("assistant-image"), runId = "run-image"),
            singleItem(
                "msg-image-otid",
                MessageExpectation(
                    id = "image-otid",
                    features = setOf(MessageFeature.Pending, MessageFeature.Attachment),
                ),
            ),
        ),
    )
}

internal object ErrorFixture {
    val fixture = ChatProjectionFixture(
        id = "visible-terminal-error",
        timelineEvents = listOf(
            TimelineEvent.Confirmed(
                position = 0.0,
                otid = "otid-user-error",
                content = "try it",
                serverId = "user-error",
                messageType = TimelineMessageType.USER,
                date = fixtureInstant,
                runId = null,
                stepId = null,
            ),
            TimelineEvent.Confirmed(
                position = 1.0,
                otid = "otid-error-1",
                content = "Model provider unavailable",
                serverId = "error-1",
                messageType = TimelineMessageType.ERROR,
                date = fixtureInstant,
                runId = null,
                stepId = null,
            ),
        ),
        expectedItems = listOf(
            singleItem(
                "msg-otid-error-1",
                MessageExpectation("error-1", features = setOf(MessageFeature.Error)),
            ),
            singleItem("msg-otid-user-error", MessageExpectation("user-error")),
        ),
    )
}

internal object ReasoningEchoFixture {
    val fixture = ChatProjectionFixture(
        id = "reasoning-assistant-echo-deduplication",
        timelineEvents = listOf(
            TimelineEvent.Confirmed(
                position = 0.0,
                otid = "otid-user-echo",
                content = "hello",
                serverId = "user-echo",
                messageType = TimelineMessageType.USER,
                date = fixtureInstant,
                runId = null,
                stepId = null,
            ),
            TimelineEvent.Confirmed(
                position = 1.0,
                otid = "otid-reasoning-echo",
                content = "same text",
                serverId = "reasoning-echo",
                messageType = TimelineMessageType.REASONING,
                date = fixtureInstant,
                runId = "run-echo",
                stepId = "step-echo",
            ),
            TimelineEvent.Confirmed(
                position = 2.0,
                otid = "otid-assistant-echo",
                content = "same text",
                serverId = "assistant-echo",
                messageType = TimelineMessageType.ASSISTANT,
                date = fixtureInstant,
                runId = "run-echo",
                stepId = "step-echo",
            ),
        ),
        expectedItems = listOf(
            singleItem(
                "run-echo",
                MessageExpectation("reasoning-echo:REASONING", features = setOf(MessageFeature.Reasoning)),
                runId = "run-echo",
            ),
            singleItem("msg-otid-user-echo", MessageExpectation("user-echo")),
        ),
    )
}
