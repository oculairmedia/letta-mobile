package com.letta.mobile.testutil

import com.letta.mobile.channel.ChannelNotification
import com.letta.mobile.channel.IChannelNotificationPublisher

class FakeChannelNotificationPublisher(
    var accepted: Boolean = true,
) : IChannelNotificationPublisher {
    val published: MutableList<ChannelNotification> = mutableListOf()

    override fun publish(notification: ChannelNotification): Boolean {
        published += notification
        return accepted
    }
}
