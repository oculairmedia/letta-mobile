package com.letta.mobile.feature.chat.coordination

internal data class RecentMessagesReconcileRequest(
    val conversationId: String,
    val reason: String,
    val connectionGeneration: Long,
)
