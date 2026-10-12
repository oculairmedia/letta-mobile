@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.onAllNodesWithText

import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatRenderItemState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.surface.ChatToolDetails
import com.letta.mobile.ui.chat.surface.RecordingChatActions
import kotlinx.collections.immutable.toImmutableSet

internal fun rowContext(
    capabilities: ChatSurfaceCapabilities = ChatSurfaceCapabilities.Default,
    itemState: ChatRenderItemState = renderState(),
    newestMessageId: String? = null,
    toolDetails: ChatToolDetails = ChatToolDetails.Inline,
): ChatRowContext = ChatRowContext(
    newestMessageId = newestMessageId,
    toolDetails = toolDetails,
    itemState = itemState,
    capabilities = capabilities,
)

/** A settled (not streaming) page's owner state: which runs are collapsed, which reasoning is open. */
internal fun renderState(
    collapsedRunIds: Set<String> = emptySet(),
    expandedReasoning: Set<String> = emptySet(),
    activeApprovalRequestId: String? = null,
): ChatRenderItemState = ChatRenderItemState(
    isStreaming = false,
    activeApprovalRequestId = activeApprovalRequestId,
    collapsedRunIds = collapsedRunIds.toImmutableSet(),
    expandedReasoningMessageIds = expandedReasoning.toImmutableSet(),
)

internal fun rowCallbacks(
    actions: ChatActions = RecordingChatActions(),
    host: ChatSurfaceHost = ChatSurfaceHost(),
    resolveAgentName: (String) -> String? = { null },
    openSubagent: ((ChatSubagentTarget) -> Unit)? = null,
): ChatRowCallbacks = ChatRowCallbacks(
    actions = actions,
    host = host,
    onImageTap = { _, _ -> },
    resolveAgentName = resolveAgentName,
    openSubagent = openSubagent,
)

internal fun message(
    id: String,
    role: String,
    content: String,
    timestamp: String = "2026-07-19T12:00:00Z",
) = UiMessage(id = id, role = role, content = content, timestamp = timestamp)

/**
 * Markdown text (agent prose, error bubbles, reasoning bodies) is parsed off the UI thread
 * (rememberMarkdownState runs the parser on Dispatchers.Default), so it reaches the tree some
 * wall-clock time after the first frame, not on the test clock. Asserting on it (or measuring a row
 * that contains it) straight after setContent races that parse. This waits on the condition itself:
 * it drives frames (so the parse result recomposes, even with `mainClock.autoAdvance = false`) until
 * the text is in the tree, and fails loudly after [timeoutMillis] of real time.
 */
internal fun ComposeUiTest.awaitText(text: String, timeoutMillis: Long = 30_000) {
    val deadline = System.nanoTime() + timeoutMillis * 1_000_000
    while (onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
        check(System.nanoTime() < deadline) { "\"$text\" never reached the semantics tree (markdown parse did not land)" }
        mainClock.advanceTimeByFrame()
        Thread.sleep(2)
    }
}
