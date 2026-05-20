package com.letta.mobile.channel

interface IChannelNotificationPublisher {
    fun publish(notification: ChannelNotification): Boolean
}
