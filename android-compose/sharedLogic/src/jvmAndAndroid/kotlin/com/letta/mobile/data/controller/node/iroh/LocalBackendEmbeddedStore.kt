package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.model.Agent
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ContextWindowOverview
import com.letta.mobile.data.model.Conversation
import com.letta.mobile.data.model.ConversationId
import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.storage.ImageBlobStore
import kotlinx.serialization.json.Json
import java.io.File

/**
 * letta-mobile-by4vm.21: the Android embedded runtime's view of its local-backend store
 * (`LETTA_LOCAL_BACKEND_DIR`), built on the same [LocalBackendLayout] and readers as the native
 * admin-query tier ([LocalBackendAdminStore]) so the on-disk format has exactly one parser.
 * The app's `LettaCodeLocalBackendStore` is a thin coroutine/DI adapter over this class.
 *
 * Blocking I/O throughout: callers dispatch to an I/O context.
 */
class LocalBackendEmbeddedStore(private val baseDir: File) {
    private val records = LocalBackendEmbeddedRecords(baseDir)
    private val transcript = LocalBackendEmbeddedTranscript(baseDir)
    private val support = LocalBackendStoreSupport(baseDir, LocalBackendAdminStore.DEFAULT_MODEL_ENDPOINT)

    // No whole-file cap: on-device transcripts are always read through BoundedTranscriptReader.
    private val contextReader =
        LocalBackendContextReader(support, LocalBackendMessageReader(support), maxTranscriptBytes = Long.MAX_VALUE)
    // Decode-only reads of files letta.js wrote (letta-mobile-by4vm.20).
    private val overviewJson: Json = LettaJson.persisted

    fun listAgents(): List<Agent> = records.listAgents()

    fun persistAgent(agent: Agent) = records.persistAgent(agent)

    /** See [LocalBackendEmbeddedRecords.seedAgent]; [now] stamps a newly created conversation record. */
    fun seedAgent(agentId: AgentId, modelHandle: String, now: () -> String = ::isoNow) =
        records.seedAgent(agentId, modelHandle, now)

    fun storedModelHandle(agentId: AgentId): String? = records.storedModelHandle(agentId)

    fun listConversations(): List<Conversation> = records.listDefaultConversations()

    fun createConversation(agentId: AgentId, summary: String?, now: () -> String = ::isoNow): Conversation =
        records.createDefaultConversation(agentId, summary, now)

    /**
     * letta-mobile-cyh28: the shared local-backend estimator over the agent's default
     * conversation, via the same [LocalBackendContextReader] that serves `agent.context`. The
     * window falls back to letta.js's 128k custom-model default when the records carry none.
     */
    fun contextWindowOverview(agentId: AgentId): ContextWindowOverview? {
        val projected = contextReader.agentContextProjected(AgentContextQuery(agentId.value, null)) ?: return null
        val overview = overviewJson.decodeFromJsonElement(ContextWindowOverview.serializer(), projected)
        return if (overview.contextWindowSizeMax > 0) overview else overview.copy(contextWindowSizeMax = DEFAULT_LOCAL_CONTEXT_WINDOW)
    }

    /** See [LocalBackendEmbeddedTranscript.readTranscript]; [blobStoreFor] opens a conversation dir's blob store. */
    fun readTranscript(agentId: AgentId, blobStoreFor: (File) -> ImageBlobStore): List<LettaMessage> =
        transcript.readTranscript(agentId, blobStoreFor)

    fun readToolResults(agentId: AgentId): Map<String, StoredToolResult> = transcript.readToolResults(agentId)

    /** The default conversation's directory (transcript, blobs, sidecars) for [agentId]. */
    fun defaultConversationDir(agentId: AgentId): File = LocalBackendLayout.defaultConversationDir(baseDir, agentId)

    /** The default conversation's `messages.jsonl` for [agentId]. */
    fun defaultTranscriptFile(agentId: AgentId): File = File(defaultConversationDir(agentId), LocalBackendLayout.TRANSCRIPT_FILE)

    companion object {
        /** letta.js customOpenAICompatibleModel default when model_settings carries no context_window_limit. */
        const val DEFAULT_LOCAL_CONTEXT_WINDOW: Int = 128_000

        /** See [LocalBackendEmbeddedRecords.localConversationIdFor]. */
        fun localConversationIdFor(agentId: AgentId): ConversationId =
            LocalBackendEmbeddedRecords.localConversationIdFor(agentId)

        private fun isoNow(): String = java.time.Instant.now().toString()
    }
}
