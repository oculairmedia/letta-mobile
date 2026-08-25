package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage

internal data class RecoveryTransportFixture(
    val messages: List<LettaMessage> = emptyList(),
    val failure: Throwable? = null,
)

internal class SnapshotRecoveryTransport(
    private val fixture: RecoveryTransportFixture,
) : TimelineTransport by EmptyTimelineTransport {
    var remoteReads: Int = 0
        private set

    override suspend fun listConversationMessages(
        conversationId: String,
        limit: Int?,
        after: String?,
        order: String?,
    ): List<LettaMessage> {
        remoteReads += 1
        fixture.failure?.let { throw it }
        return fixture.messages
    }
}
