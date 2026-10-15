package com.letta.mobile.desktop.chat

import com.letta.mobile.ui.chat.session.ChatWorkingDirectoryTarget

/**
 * letta-mobile-by4vm.98: [conversation]'s working directory on this gateway, for the shared
 * [com.letta.mobile.ui.chat.session.ChatWorkingDirectoryState]; null when the gateway cannot report
 * one or the conversation has no agent yet.
 */
internal fun DesktopChatGateway?.workingDirectoryTarget(conversation: DesktopConversationSummary?): ChatWorkingDirectoryTarget? {
    val runtime = this as? DesktopWorkingDirectoryController ?: return null
    val agentId = conversation?.agentId ?: return null
    val conversationId = conversation.id
    return object : ChatWorkingDirectoryTarget {
        override suspend fun read(): String? = runtime.currentWorkingDirectory(agentId, conversationId)

        override suspend fun write(path: String): Boolean = runtime.setWorkingDirectory(agentId, conversationId, path)
    }
}
