package com.letta.mobile.runtime.local

import android.content.Context
import com.letta.mobile.data.controller.node.iroh.LocalBackendEmbeddedStore
import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.repository.api.LocalRuntimeAgentSource
import com.letta.mobile.data.repository.api.LocalRuntimeConversationSource
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A persisted pi-ai toolResult row; parsed by the shared [LocalBackendEmbeddedStore.readToolResults]. */
typealias StoredToolResult = com.letta.mobile.data.controller.node.iroh.StoredToolResult

/**
 * The embedded runtime's handle on the on-device letta.js local-backend store
 * (LETTA_LOCAL_BACKEND_DIR = `filesDir/embedded-lettacode/local-backend`).
 *
 * letta-mobile-by4vm.21: a thin adapter. Every read and seed of the on-disk format goes through
 * sharedLogic's [LocalBackendEmbeddedStore] (layout in `LocalBackendLayout`), the same code the
 * native admin-query tier uses, so a letta.js layout change is fixed in one place. This class
 * only binds it to the app (Hilt, the I/O dispatcher, the image blob store) and runs the
 * app-owned transcript mutation passes ([LocalConversationHealer], [LocalImageContextStripper]).
 *
 * letta.js runs with strictAgentAccess/strictConversationAccess, so both records must exist before
 * a turn; [seedAgent] creates them. letta.js only loads this store at process start — external
 * writes are invisible to a running session.
 */
@Singleton
class LettaCodeLocalBackendStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : LocalRuntimeConversationSource, LocalRuntimeAgentSource {
    private val conversationHealer = LocalConversationHealer()

    val storageDirectory: File
        get() = File(context.filesDir, "embedded-lettacode/local-backend")

    private val backend: LocalBackendEmbeddedStore by lazy { LocalBackendEmbeddedStore(storageDirectory) }

    override suspend fun listAgents(): List<Agent> = io { backend.listAgents() }

    override suspend fun persistAgent(agent: Agent) = io { backend.persistAgent(agent) }

    /** letta-mobile-cyh28: see [LocalBackendEmbeddedStore.contextWindowOverview]. */
    override suspend fun contextWindowOverview(agentId: AgentId): ContextWindowOverview? =
        io { backend.contextWindowOverview(agentId) }

    /** See [LocalBackendEmbeddedStore.seedAgent]. */
    suspend fun seedAgent(agentId: String, modelHandle: String) = io { backend.seedAgent(AgentId(agentId), modelHandle) }

    /**
     * Conversations letta.js has persisted. The on-disk default conversation is exposed under the
     * stable id [localConversationIdFor] — the id the chat send path uses. Works with no node
     * process running (cold app start).
     */
    override suspend fun listConversations(): List<Conversation> = io { backend.listConversations() }

    override suspend fun createConversation(agentId: AgentId, summary: String?): Conversation =
        io { backend.createConversation(agentId, summary) }

    /**
     * The agent's stored model handle. The controller passes this back as --model on session
     * start: letta.js overwrites the agent's model with whatever --model says, so anything other
     * than the stored value would clobber per-agent model selection.
     */
    suspend fun storedModelHandle(agentId: AgentId): String? = io { backend.storedModelHandle(agentId) }

    /**
     * The agent's default-conversation transcript as timeline messages, so the chat screen can
     * hydrate history across app restarts (bounded read, image_ref resolution: see
     * [LocalBackendEmbeddedStore.readTranscript]).
     */
    suspend fun readTranscript(agentId: String): List<LettaMessage> =
        io { backend.readTranscript(AgentId(agentId), ::LocalImageBlobStore) }

    /** Persisted tool results keyed by tool_call_id, attached at turn end (letta-mobile-bm6x2). */
    suspend fun readToolResults(agentId: AgentId): Map<String, StoredToolResult> = io { backend.readToolResults(agentId) }

    /**
     * Heals the agent's default-conversation transcript by settling any dangling tool calls — the
     * on-device analogue of the shim's lcp-ezv healer. Idempotent; safe before every turn and
     * after an interrupted one.
     */
    suspend fun healDanglingToolCalls(agentId: String): LocalConversationHealer.HealReport = io {
        conversationHealer.healTranscript(backend.defaultTranscriptFile(AgentId(agentId)))
    }

    /**
     * Strips heavy base64 image data from the persisted default conversation into the blob store,
     * leaving an image_ref pointer (letta-mobile-87itk). Idempotent; safe to call pre-turn.
     */
    suspend fun stripPersistedImageData(agentId: String): LocalImageContextStripper.StripReport = io {
        val id = AgentId(agentId)
        LocalImageContextStripper(blobStore = LocalImageBlobStore(backend.defaultConversationDir(id)))
            .stripTranscript(backend.defaultTranscriptFile(id))
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** See [LocalBackendEmbeddedStore.localConversationIdFor]. */
        fun localConversationIdFor(agentId: String): String = LocalBackendEmbeddedStore.localConversationIdFor(agentId)
    }
}
