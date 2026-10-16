package com.letta.mobile.channel

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Constructor-injected collaborators for [ChatPushService].
 *
 * Android `@AndroidEntryPoint` services cannot use constructor injection on the
 * Service itself; Hilt field-injects this facade instead of individual deps
 * (letta-mobile-l2ew9.2).
 */
@Singleton
class ChatPushServiceDependencies @Inject constructor(
    val channelNotificationPublisher: ChannelNotificationPublisher,
    /** letta-mobile-bzvro.27: approval notifications, watched for as long as the service lives. */
    val approvalAttention: AndroidApprovalAttention,
)
