package com.letta.mobile.feature.editagent

import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.AgentUpdateParams
import com.letta.mobile.data.repository.api.IAgentRepository
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.ui.mascot.mascotIdentitySettingsKey
import com.letta.mobile.ui.mascot.withinAgentMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Persists the chosen mascot identity: in the device cache at once (so this client shows it offline
 * and before the roster refreshes) and on the agent's metadata (so every client shows it).
 *
 * Agent writes go out one at a time and only the newest pending identity is sent, so a quick run of
 * picks can never land out of order and leave the agent on an older one than the device cache. Each
 * write merges into the metadata the previous successful write sent, else the form's loaded metadata.
 */
internal class EditAgentMascotIdentityWriter(
    private val agentId: String,
    private val agentRepository: IAgentRepository,
    private val settings: SecureSettingsStore,
    scope: CoroutineScope,
    private val loadedMetadata: () -> Map<String, JsonElement>?,
    private val onFailed: (Exception) -> Unit = {},
) {
    private val pending = Channel<MascotIdentity>(Channel.CONFLATED)
    private var writtenMetadata: Map<String, JsonElement>? = null

    init {
        scope.launch { for (identity in pending) push(identity) }
    }

    fun write(identity: MascotIdentity) {
        settings.putString(mascotIdentitySettingsKey(agentId), identity.encode())
        pending.trySend(identity)
    }

    private suspend fun push(identity: MascotIdentity) {
        val metadata = identity.withinAgentMetadata(writtenMetadata ?: loadedMetadata())
        try {
            agentRepository.updateAgent(AgentId(agentId), AgentUpdateParams(metadata = metadata))
            // What was sent, not the response: a transport that answers with a partial agent must
            // not make the next write drop the agent's other metadata.
            writtenMetadata = metadata
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailed(e)
        }
    }
}
