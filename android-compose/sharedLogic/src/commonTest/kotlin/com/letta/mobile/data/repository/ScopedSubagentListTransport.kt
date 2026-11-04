package com.letta.mobile.data.repository

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.model.SubagentStatus
import com.letta.mobile.data.repository.api.SubagentParentScope
import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.NoOpChannelTransport
import com.letta.mobile.data.transport.api.SubagentScopeAwareChannelTransport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * letta-mobile-fxoew: a transport whose `subagent.list` is conversation-scoped
 * like the host's. The call-time-scoped [sendSubagentList] reports the
 * production "scope unavailable" failure, so only scoped fetches see entries.
 */
internal class ScopedSubagentListTransport : NoOpChannelTransport(), SubagentScopeAwareChannelTransport {
    override val state = MutableStateFlow<ChannelTransportState>(connected())
    override val events = MutableSharedFlow<ServerFrame>(extraBufferCapacity = 16)

    /** Entries the host holds, keyed by parent conversation id. */
    val entriesByConversation = mutableMapOf<String, List<SubagentEntry>>()

    /** Conversation ids requested through the scoped fetch, in order. */
    val requestedConversations = mutableListOf<String>()

    /** Scoped fetches to fail (as "scope unavailable") before answering. */
    var failuresBeforeSuccess = 0

    /**
     * The conversation the device is viewing, which the call-time-scoped
     * [sendSubagentList] targets. Null reproduces "scope unavailable".
     */
    var viewedConversation: String? = null

    override suspend fun sendSubagentList(all: Boolean, timeoutMs: Long): ServerFrame.SubagentListResponse =
        viewedConversation?.let(::success) ?: failure()

    override suspend fun sendSubagentListForScope(
        scope: SubagentParentScope,
        all: Boolean,
        timeoutMs: Long,
    ): ServerFrame.SubagentListResponse {
        requestedConversations += scope.parentConversationId
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess -= 1
            return failure()
        }
        return success(scope.parentConversationId)
    }

    /** When true, deliver the host notification before its matching receipt. */
    var pushBeforeReceipt = false

    override suspend fun dismissSubagent(scope: SubagentParentScope, toolCallId: String): Result<List<SubagentEntry>> {
        val entry = entriesByConversation[scope.parentConversationId].orEmpty().singleOrNull {
            it.parentAgentId == scope.parentAgentId && it.parentConversationId == scope.parentConversationId &&
                it.toolCallId == toolCallId && toolCallId.isNotBlank()
        } ?: return Result.failure(IllegalArgumentException("unknown scoped dispatch"))
        val terminal = entry.copy(status = SubagentStatus.CANCELLED, terminalReason = "user_dismissed")
        if (pushBeforeReceipt) {
            events.emit(ServerFrame.SubagentsUpdated(
                id = "before-receipt", ts = "t", reason = "registry", subagentsActive = listOf(terminal),
                parentAgentId = scope.parentAgentId, parentConversationId = scope.parentConversationId,
            ))
            kotlinx.coroutines.yield()
        }
        return Result.success(listOf(terminal))
    }

    private fun success(conversationId: String) = ServerFrame.SubagentListResponse(
        id = "list-$conversationId",
        ts = "t",
        success = true,
        subagents = entriesByConversation[conversationId].orEmpty(),
    )

    private fun failure() = ServerFrame.SubagentListResponse(
        id = "list-failed",
        ts = "t",
        success = false,
        error = "subagent scope unavailable; hydrate a conversation first",
    )

    companion object {
        const val PARENT_AGENT = "agent-parent"

        fun connected() = ChannelTransportState.Connected(serverId = "srv", sessionId = "sess", deviceId = "dev")

        fun running(toolCallId: String, conversationId: String) = SubagentEntry(
            toolCallId = toolCallId,
            subagentType = "General-purpose",
            status = SubagentStatus.RUNNING,
            parentAgentId = PARENT_AGENT,
            parentConversationId = conversationId,
        )
    }
}
