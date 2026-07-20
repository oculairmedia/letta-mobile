package com.letta.mobile.data.chat.runtime

import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import kotlin.jvm.JvmInline

/** Optional transport capability for persisting a generated conversation title. */
interface ConversationSummaryGateway {
    suspend fun setConversationSummary(update: ConversationSummaryUpdate): Conversation
}

data class ConversationSummaryUpdate(
    val conversationId: ConversationId,
    val summary: ConversationSummary,
)

@JvmInline
value class ConversationSummary(val value: String)
