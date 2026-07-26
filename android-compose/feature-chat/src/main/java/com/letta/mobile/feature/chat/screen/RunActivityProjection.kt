package com.letta.mobile.feature.chat.screen

import com.letta.mobile.data.chat.projection.projectRunActivity as projectSharedRunActivity
import com.letta.mobile.data.model.UiMessage

internal typealias RunActivityState = com.letta.mobile.data.chat.projection.RunActivityState
internal typealias RunActivityProjection = com.letta.mobile.data.chat.projection.RunActivityProjection

internal fun projectRunActivity(
    messages: List<UiMessage>,
    isStreaming: Boolean,
): RunActivityProjection? = projectSharedRunActivity(
    messages = messages,
    isActiveRunStreaming = isStreaming,
)
