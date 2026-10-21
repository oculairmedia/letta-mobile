package com.letta.mobile.web.data

import com.letta.mobile.data.chat.branch.ConversationForkGateway
import com.letta.mobile.data.chat.recompile.ConversationRecompileGateway
import com.letta.mobile.data.chat.runtime.ChatConversationStore
import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.chat.runtime.ConversationSummaryGateway
import com.letta.mobile.data.repository.appserver.AppServerConversationAdmin
import com.letta.mobile.data.repository.appserver.AppServerLocalAdminGateway
import com.letta.mobile.data.timeline.TimelineTransport

/**
 * A ws:// session as a [ChatGateway] for the shared conversation owner: its timeline, and the
 * shared App Server conversation commands ([AppServerConversationAdmin], the ones desktop's App
 * Server gateway answers with). Over Iroh the session's shared Iroh chat gateway serves instead.
 */
internal class SessionChatGateway(admin: AppServerLocalAdminGateway, timeline: TimelineTransport) :
    ChatGateway,
    TimelineTransport by timeline,
    ChatConversationStore by AppServerConversationAdmin(admin),
    ChatGatewayExtras by AppServerConversationAdmin(admin),
    ConversationSummaryGateway by AppServerConversationAdmin(admin),
    ConversationForkGateway by AppServerConversationAdmin(admin),
    ConversationRecompileGateway by AppServerConversationAdmin(admin)
