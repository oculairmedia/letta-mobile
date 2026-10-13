package com.letta.mobile.channel

import com.letta.mobile.data.channel.CurrentConversationTracker
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.repository.api.IAllConversationsRepository
import com.letta.mobile.data.repository.api.IConversationRepository
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
    /** letta-mobile-ckmub.4: conversations come from the active backend's repositories, not REST. */
    val conversationRepository: IConversationRepository,
    val allConversationsRepository: IAllConversationsRepository,
    val agentRepository: IAgentRepository,
    val notificationDeliveryCoordinator: NotificationDeliveryCoordinator,
    val channelNotificationPublisher: ChannelNotificationPublisher,
    val currentConversationTracker: CurrentConversationTracker,
    /** letta-mobile-bzvro.27: approval notifications, watched for as long as the service lives. */
    val approvalAttention: AndroidApprovalAttention,
)
