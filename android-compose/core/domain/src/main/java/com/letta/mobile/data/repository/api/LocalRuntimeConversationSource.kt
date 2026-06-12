package com.letta.mobile.data.repository.api

import com.letta.mobile.data.model.Conversation

/**
 * Conversations for local-runtime (embedded LettaCode) agents.
 *
 * Implemented by the app layer's local backend store, which owns all
 * knowledge of the on-device letta.js storage format. Repositories route
 * to this source instead of the remote API when the active config binds
 * to the local runtime, so screens need no per-ViewModel branching.
 */
fun interface LocalRuntimeConversationSource {
    suspend fun listConversations(): List<Conversation>
}
