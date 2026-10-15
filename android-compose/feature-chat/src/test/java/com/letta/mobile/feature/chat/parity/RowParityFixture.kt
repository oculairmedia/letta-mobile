package com.letta.mobile.feature.chat.parity

import com.letta.mobile.data.model.UiApprovalRequest
import com.letta.mobile.data.model.UiApprovalToolCall
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.data.model.UiToolCall
import com.letta.mobile.data.runtime.PendingApprovalDetails
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import kotlinx.collections.immutable.toPersistentList

/**
 * letta-mobile-ckmub.1 (hard cut S1): the one realistic conversation the row goldens draw through
 * the shared timeline. Every [ParityRow] is a slice of [timeline] by message id. (Until hard cut S9
 * the same slices were drawn through the legacy Android timeline too, for the side-by-side parity.)
 */
internal object RowParityFixture {
    const val AGENT_ID = "agent-parity"
    const val AGENT_NAME = "Meridian"
    const val CONVERSATION_ID = "conv-parity"

    private const val RUN = "run-parity-1"
    private const val APPROVAL_RUN = "run-parity-2"
    private const val APPROVAL_REQUEST = "approval-parity-1"
    private const val APPROVAL_TOOL_CALL = "tc-approval"
    private const val APPROVAL_ARGS = """{"command":"./gradlew :feature-chat:testDebugUnitTest"}"""
    private const val TOOL_MS = 1_200L
    private const val REASONING_MS = 2_400L

    val userPrompt = UiMessage(
        id = "u1",
        role = "user",
        content = "Can you run the chat tests and fix whatever is failing?",
        timestamp = "2026-10-11T16:02:00Z",
    )

    val reasoning = UiMessage(
        id = "r1",
        role = "assistant",
        content = "The user wants the chat tests run. I'll run the unit tests first, then read the failure.\n" +
            "After that I'll patch the assertion.",
        timestamp = "2026-10-11T16:02:01Z",
        runId = RUN,
        stepId = "s1",
        isReasoning = true,
        latencyMs = REASONING_MS,
    )

    val toolCallReturn = UiMessage(
        id = "t1",
        role = "assistant",
        content = "",
        timestamp = "2026-10-11T16:02:04Z",
        runId = RUN,
        stepId = "s2",
        toolCalls = listOf(
            UiToolCall(
                name = "Bash",
                arguments = """{"command":"git status --short"}""",
                result = " M feature-chat/src/test/java/ChatRowTest.kt",
                status = "success",
                toolCallId = "tc1",
                executionTimeMs = TOOL_MS,
            ),
        ),
    )

    val toolCallFailed = UiMessage(
        id = "t2",
        role = "assistant",
        content = "",
        timestamp = "2026-10-11T16:02:07Z",
        runId = RUN,
        stepId = "s3",
        toolCalls = listOf(
            UiToolCall(
                name = "Bash",
                arguments = """{"command":"./gradlew :feature-chat:test"}""",
                result = "FAILED: ChatRowTest > clock",
                status = "error",
                toolCallId = "tc2",
                executionTimeMs = TOOL_MS,
            ),
        ),
    )

    val assistantReply = UiMessage(
        id = "a1",
        role = "assistant",
        content = "The clock assertion in `ChatRowTest` expected a 24-hour time. I fixed it and the suite passes now.",
        timestamp = "2026-10-11T16:02:15Z",
        runId = RUN,
        stepId = "s4",
    )

    /** An assistant message outside any run: history that predates run tracking. */
    val assistantStandalone = UiMessage(
        id = "a0",
        role = "assistant",
        content = "Morning! Two open beads today: the chat parity epic and the dock polish.",
        timestamp = "2026-10-11T08:00:05Z",
    )

    val approval = UiMessage(
        id = "ap1",
        role = "assistant",
        content = "",
        timestamp = "2026-10-11T16:04:02Z",
        runId = APPROVAL_RUN,
        stepId = "s5",
        toolCalls = listOf(
            UiToolCall(
                name = "Bash",
                arguments = APPROVAL_ARGS,
                result = null,
                status = null,
                toolCallId = APPROVAL_TOOL_CALL,
            ),
        ),
        approvalRequest = UiApprovalRequest(
            requestId = APPROVAL_REQUEST,
            toolCalls = listOf(UiApprovalToolCall(toolCallId = APPROVAL_TOOL_CALL, name = "Bash", arguments = APPROVAL_ARGS)),
            details = PendingApprovalDetails(approvalId = APPROVAL_REQUEST, toolCallId = APPROVAL_TOOL_CALL, toolName = "Bash"),
        ),
    )

    val error = UiMessage(
        id = "e1",
        role = "assistant",
        content = "Run failed: the model provider returned 429 (rate limit exceeded). Try again in a minute.",
        timestamp = "2026-10-11T16:05:00Z",
        runId = "run-parity-3",
        isError = true,
    )

    /** A prompt sent mid-run and not yet acknowledged: the queued local row. */
    val queued = UiMessage(
        id = "q1",
        role = "user",
        content = "Also update the changelog when you're done.",
        timestamp = "2026-10-11T16:05:30Z",
        clientMessageId = "otid-queued-1",
        isPending = true,
    )

    /** The prompt the agent is thinking about in the thinking rows. */
    val thinkingPrompt = UiMessage(
        id = "u2",
        role = "user",
        content = "Great. Now update the changelog.",
        timestamp = "2026-10-11T16:06:00Z",
    )

    /** The whole conversation, in order. */
    val timeline: List<UiMessage> = listOf(
        assistantStandalone,
        userPrompt,
        reasoning,
        toolCallReturn,
        toolCallFailed,
        assistantReply,
        approval,
        error,
        queued,
    )

    fun state(messages: List<UiMessage>, thinking: Boolean = false) = ChatUiState(
        conversationState = ConversationState.Ready(CONVERSATION_ID),
        messages = messages.toPersistentList(),
        isLoadingMessages = false,
        isStreaming = thinking,
        isAgentTyping = thinking,
        agentName = AGENT_NAME,
        agentId = AGENT_ID,
    )
}

/** How a row is drawn. */
internal enum class ParityLayout {
    /** Timeline only: ChatTimelineSnapshot. */
    TIMELINE,

    /**
     * Timeline plus the composer's thinking token. The token belongs to the composer, which docks at
     * the foot of the page, and a short chat reads from the top (letta-mobile-ckmub.10), so this
     * golden is page-shaped: the prompt at the top, the token at the bottom (letta-mobile-by4vm.64).
     */
    THINKING,

    /** The ChatSurface page with the agent's mascot in its COMPOSER_COMPANION seat (page-shaped, as [THINKING]). */
    THINKING_MASCOT,
}

/** One row type: a slice of [RowParityFixture.timeline] (or the thinking prompt). */
internal data class ParityRow(
    val id: String,
    val label: String,
    val messages: List<UiMessage>,
    val layout: ParityLayout = ParityLayout.TIMELINE,
) {
    val state: ChatUiState
        get() = RowParityFixture.state(messages, thinking = layout != ParityLayout.TIMELINE)

    companion object {
        private val f = RowParityFixture

        val all: List<ParityRow> = listOf(
            ParityRow("user", "User bubble", listOf(f.userPrompt)),
            ParityRow("assistant", "Assistant bubble (no run)", listOf(f.assistantStandalone)),
            ParityRow("reasoning", "Reasoning (ChatRowReasoning)", listOf(f.reasoning)),
            ParityRow("tool-call-return", "Tool call + return (ChatRowToolRun)", listOf(f.toolCallReturn)),
            ParityRow("tool-call-error", "Tool call, failed return", listOf(f.toolCallFailed)),
            ParityRow("assistant-in-run", "Assistant reply in a run", listOf(f.assistantReply)),
            ParityRow(
                "run-block",
                "Run block (ChatRowRunBlock)",
                listOf(f.userPrompt, f.reasoning, f.toolCallReturn, f.toolCallFailed, f.assistantReply),
            ),
            ParityRow("approval", "Approval card", listOf(f.approval)),
            ParityRow("error", "Error frame", listOf(f.error)),
            ParityRow("queued", "Queued (pending) user prompt", listOf(f.queued)),
            ParityRow("thinking", "Thinking row (no mascot)", listOf(f.thinkingPrompt), ParityLayout.THINKING),
            ParityRow("thinking-mascot", "Thinking row with mascot seat", listOf(f.thinkingPrompt), ParityLayout.THINKING_MASCOT),
            ParityRow("timeline", "Whole fixture timeline", f.timeline),
        )
    }
}
