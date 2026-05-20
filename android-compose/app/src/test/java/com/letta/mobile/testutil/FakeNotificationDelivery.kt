package com.letta.mobile.testutil

import com.letta.mobile.data.channel.NotificationDelivery
import com.letta.mobile.data.channel.NotificationDeliveryCandidate
import com.letta.mobile.data.channel.NotificationDeliveryDecision

class FakeNotificationDelivery(
    var decision: NotificationDeliveryDecision,
) : NotificationDelivery {
    val submitted: MutableList<NotificationDeliveryCandidate> = mutableListOf()

    override fun submit(candidate: NotificationDeliveryCandidate): NotificationDeliveryDecision {
        submitted += candidate
        return decision
    }
}
