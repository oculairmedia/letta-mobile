package com.letta.mobile.web.data

import com.letta.mobile.data.chat.branch.ConversationForkGateway
import com.letta.mobile.data.chat.recompile.ConversationRecompileGateway
import com.letta.mobile.data.chat.runtime.ChatConversationStore
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.runtime.ConversationSummaryGateway
import com.letta.mobile.data.repository.appserver.AppServerConversationAdmin
import com.letta.mobile.data.repository.appserver.AppServerLocalAdminGateway
import com.letta.mobile.data.repository.conversationChanges
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.data.transport.appserver.stopBackgroundProcess
import com.letta.mobile.data.transport.api.IChannelTransport

/**
 * A ws:// session as a [ChatGateway] for the shared conversation owner: its timeline, and the
 * shared App Server conversation commands ([AppServerConversationAdmin], the ones desktop's App
 * Server gateway answers with). The session graph's channel supplies conversation pushes to the
 * shared list owner. Over Iroh the session's shared Iroh chat gateway serves instead.
 */
internal class SessionChatGateway(
    admin: AppServerLocalAdminGateway,
    timeline: TimelineTransport,
    channel: IChannelTransport,
    client: com.letta.mobile.data.transport.appserver.AppServerClient,
) :
    ChatGateway,
    TimelineTransport by timeline,
    ChatConversationStore by AppServerConversationAdmin(admin),
    ChatGatewayExtras by AppServerConversationAdmin(admin),
    ConversationSummaryGateway by AppServerConversationAdmin(admin),
    ConversationForkGateway by AppServerConversationAdmin(admin),
    ConversationRecompileGateway by AppServerConversationAdmin(admin) {
    override val deviceStatuses = channel.deviceStatuses
    override val stopBackgroundProcess: suspend (com.letta.mobile.data.transport.appserver.AppServerRuntimeScope, String) -> Unit =
        { runtime, id -> client.stopBackgroundProcess(runtime, com.letta.mobile.data.model.process.ProcessId(id)) }
    override val conversationChanges = channel.conversationChanges()
}
