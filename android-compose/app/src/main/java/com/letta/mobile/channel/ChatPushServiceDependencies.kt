package com.letta.mobile.channel

import com.letta.mobile.data.api.ConversationApi
import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.timeline.TimelineRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Constructor-injected collaborators for [ChatPushService].
 *
 * Android `@AndroidEntryPoint` services cannot use constructor injection on the
 * Service itself; Hilt field-injects this facade instead of six individual deps
 * (letta-mobile-l2ew9.2).
 */
@Singleton
class ChatPushServiceDependencies @Inject constructor(
    val timelineRepository: TimelineRepository,
    val conversationApi: ConversationApi,
    val agentRepository: IAgentRepository,
    val notificationDeliveryCoordinator: NotificationDeliveryCoordinator,
    val channelNotificationPublisher: ChannelNotificationPublisher,
    val currentConversationTracker: CurrentConversationTracker,
)
