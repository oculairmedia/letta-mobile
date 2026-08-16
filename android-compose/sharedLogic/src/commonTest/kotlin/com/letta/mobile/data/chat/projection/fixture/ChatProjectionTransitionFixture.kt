package com.letta.mobile.data.chat.projection.fixture

import com.letta.mobile.data.chat.projection.ChatMessageListChange
import com.letta.mobile.data.timeline.DeliveryState
import com.letta.mobile.data.timeline.Role
import com.letta.mobile.data.timeline.TimelineEvent
import com.letta.mobile.data.timeline.TimelineMessageType
import com.letta.mobile.data.timeline.parseTimelineInstant

internal object StreamingTransitionFixture {
    private val instant = parseTimelineInstant("2026-08-16T01:00:00Z")
    private val user = TimelineEvent.Confirmed(
        position = 0.0,
        otid = "otid-transition-user",
        content = "hello",
        serverId = "transition-user",
        messageType = TimelineMessageType.USER,
        date = instant,
        runId = null,
        stepId = null,
    )
    private val pending = TimelineEvent.Local(
        position = 1.0,
        otid = "transition-assistant",
        content = "H",
        role = Role.ASSISTANT,
        sentAt = instant,
        deliveryState = DeliveryState.SENDING,
        messageType = TimelineMessageType.ASSISTANT,
    )
    private val growing = pending.copy(content = "Hello")
    private val settled = growing.copy(deliveryState = DeliveryState.SENT)
    private val rewrittenHistory = user.copy(content = "hello edited")

    val sequence = ProjectionTransitionSequence(
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
