package com.letta.mobile.data.shell

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId

internal val ShellDestination.isChatPage: Boolean
    get() = this is ShellDestination.Chat || this is ShellDestination.Canvas

/** Preserve payload (including Canvas) while projecting the owner's identity. */
internal fun ShellDestination.refinedBy(agent: String?, conversation: String?): ShellDestination {
    return when (this) {
        is ShellDestination.Chat -> copy(agentId = agent, conversationId = conversation)
        is ShellDestination.Canvas -> copy(agentId = agent, conversationId = conversation)
        is ShellDestination.Memory -> ShellDestination.Memory(agent)
        is ShellDestination.Schedules -> ShellDestination.Schedules(agent)
        is ShellDestination.Skills -> copy(agentId = agent)
        else -> this
    }
}

internal fun ShellDestination.initialFocus(): CurrentFocus {
    return CurrentFocus(
        pageRoute = ShellRoute.encode(if (this is ShellDestination.AgentEditor) ShellDestination.Chat() else refinedBy(null, null)),
        agentId = agentId?.takeUnless { this is ShellDestination.AgentEditor }?.let(::AgentId),
        conversationId = conversationId?.let(::ConversationId),
        chatMode = if (conversationId == null) CurrentFocusChatMode.Resume else CurrentFocusChatMode.Exact,
    )
}

/** Construct a validated navigation envelope, before binding or for a source reset. */
internal fun initialFocusSnapshot(
    page: ShellDestination,
    sourceKey: String = "",
    railExpanded: Boolean = false,
): CurrentFocusSnapshot {
    return CurrentFocusSnapshot(
        sourceKey = sourceKey,
        focus = page.initialFocus(),
        editorRoute = (page as? ShellDestination.AgentEditor)?.let(ShellRoute::encode),
        previewConversationId = page.conversationId?.takeIf { page.isChatPage }?.let(::ConversationId),
        railExpanded = railExpanded,
    ).also { require(it.copy(sourceKey = sourceKey.ifBlank { "unbound" }).valid()) }
}
