package com.letta.mobile.data.runtime

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId

/**
 * letta-mobile-k5nbq.9: applies an agent's saved default cwd to a newly created conversation.
 * [write] is `change_device_state{cwd}` (or the host's equivalent). A missing default is success:
 * the conversation keeps the runtime's boot directory.
 */
class AgentDefaultCwdApplier(private val defaults: AgentDefaultsRepository) {
    /** The directory a new conversation for [agentId] should start in, or null when none is saved. */
    fun cwdFor(agentId: AgentId): String? = defaults.cwd(agentId)

    /**
     * Writes [agentId]'s default cwd for [conversationId]. True when there was nothing to apply,
     * or when [write] accepted the path.
     */
    suspend fun applyOnCreate(agentId: AgentId, conversationId: ConversationId, write: CwdWrite): Boolean {
        val cwd = cwdFor(agentId) ?: return true
        return write.set(agentId, conversationId, cwd)
    }
}

/** Sets [conversationId]'s working directory to [cwd]. */
fun interface CwdWrite {
    suspend fun set(agentId: AgentId, conversationId: ConversationId, cwd: String): Boolean
}
