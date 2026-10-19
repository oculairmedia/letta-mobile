package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.chat.runtime.ChatGateway
import com.letta.mobile.data.chat.runtime.ChatGatewayExtras
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.repository.appserver.AppServerLocalAdminGateway
import com.letta.mobile.data.repository.modelcontrol.ConversationEffortGateway
import com.letta.mobile.data.repository.modelcontrol.ConversationModelChange
import com.letta.mobile.data.repository.modelcontrol.ReasoningEffortChoice

/**
 * Where a chat's model list comes from and where a per-conversation
 * switch goes, for [ConversationModels]. Desktop builds it from its chat gateway ([of]); a host that
 * holds only an App Server session builds it from that session's admin reads ([of] with an
 * [AppServerLocalAdminGateway]), so both run the same switch.
 */
class ConversationModelBackend(
    private val list: suspend () -> List<LlmModel>,
    /** The model-only switch (`conversation_update`); it cannot carry an effort. */
    private val setModel: suspend (ConversationModelChange) -> Conversation,
    /** The switch that carries a reasoning effort (`model.update`), where the backend has one. */
    private val efforts: ConversationEffortGateway? = null,
) {
    /** True when a switch can carry a reasoning effort; the composer shows its effort chip only then. */
    val supportsReasoningEffort: Boolean get() = efforts != null

    suspend fun listModels(): List<LlmModel> = list()

    /**
     * Sends [change]: with the effort where the backend carries one, else model only. A backend
     * without the effort refuses an effort pick rather than drop it silently.
     */
    suspend fun switchModel(change: ConversationModelChange): Conversation {
        efforts?.let { return it.updateConversationModel(change) }
        check(change.effort == ReasoningEffortChoice.Unchanged) { "This backend cannot change a conversation's reasoning effort" }
        return setModel(change)
    }

    companion object {
        /** A chat gateway's models, or null when it cannot list or switch them. */
        fun of(gateway: ChatGateway?): ConversationModelBackend? {
            val extras = gateway as? ChatGatewayExtras ?: return null
            return ConversationModelBackend(
                list = extras::listLlmModels,
                setModel = { change -> extras.setConversationModel(change.conversationId.value, change.model.value) },
                efforts = gateway as? ConversationEffortGateway,
            )
        }

        /** An App Server session's models, read and switched over its own protocol commands. */
        fun of(admin: AppServerLocalAdminGateway): ConversationModelBackend = ConversationModelBackend(
            list = admin::listLlmModels,
            setModel = { change -> admin.setConversationModel(change.conversationId.value, change.model.value) },
        )
    }
}
