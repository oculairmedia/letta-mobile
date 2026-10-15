package com.letta.mobile.desktop.chat

import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.ui.chat.session.ChatPermissionModeTarget
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

/**
 * letta-mobile-bzvro.13, by4vm.98: [conversation]'s runtime for the shared
 * [com.letta.mobile.ui.chat.session.GatewayPermissionModes]; null (no chip) when the gateway cannot
 * govern modes or the conversation has no agent yet.
 */
internal fun DesktopChatGateway?.permissionModeTarget(conversation: DesktopConversationSummary?): ChatPermissionModeTarget? {
    val modes = this as? DesktopPermissionModeController ?: return null
    val agentId = conversation?.agentId?.takeIf(String::isNotBlank) ?: return null
    return ChatPermissionModeTarget(modes, AppServerRuntimeScope(agentId, conversation.id))
}
