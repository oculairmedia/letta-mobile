package com.letta.mobile.ui.mascot

import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.storage.SecureSettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * Persists a chosen mascot identity (moved from feature-editagent, letta-mobile-ckmub.3.24): in the
 * device cache at once (so this client shows it offline and before the roster refreshes) and on the
 * agent's metadata through [push] (so every client shows it).
 *
 * Agent writes go out one at a time and only the newest pending identity is sent, so a quick run of
 * picks can never land out of order and leave the agent on an older one than the device cache. Each
 * write merges into the metadata the previous successful write sent, else [loadedMetadata].
 */
class AgentMascotIdentityWriter(
    private val agentId: String,
    private val settings: SecureSettingsStore,
    scope: CoroutineScope,
    private val loadedMetadata: () -> Map<String, JsonElement>?,
    private val push: suspend (Map<String, JsonElement>) -> Unit,
    private val onFailed: (Exception) -> Unit = {},
) {
    private val pending = Channel<MascotIdentity>(Channel.CONFLATED)
    private var writtenMetadata: Map<String, JsonElement>? = null

    init {
        scope.launch { for (identity in pending) send(identity) }
    }

    fun write(identity: MascotIdentity) {
        settings.putString(mascotIdentitySettingsKey(agentId), identity.encode())
        pending.trySend(identity)
    }

    private suspend fun send(identity: MascotIdentity) {
        val metadata = identity.withinAgentMetadata(writtenMetadata ?: loadedMetadata())
        try {
            push(metadata)
            // What was sent, not a response: a transport that answers with a partial agent must not
            // make the next write drop the agent's other metadata.
            writtenMetadata = metadata
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onFailed(e)
        }
    }
}
