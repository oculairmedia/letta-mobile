package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import java.io.File

/**
 * lgns8.9: native admin-query tier that reads the letta-code on-disk backend
 * store DIRECTLY, replacing the lettashim HTTP proxy for reads. lettashim is not
 * a proxy — its admin queries are `admin-shim/lib/store.ts` functions reading
 * `<baseDir>/agents/{id}.json` + `<baseDir>/memfs/<agentId>/memory/system/{label}.md`. This
 * ports the `agent.list` read + the `translate.ts:agentToLettaState` projection so
 * the wrapper serves it without the shim.
 *
 * SAFE BY CONSTRUCTION: constructed only when [baseDir] is explicitly configured
 * (the wrapper leaves it null unless LETTA_LOCAL_BACKEND_DIR is set), and every
 * public reader returns null on ANY failure so the caller falls back to the shim
 * proxy. Until a live parity check flips the wrapper flag on, this code cannot
 * affect production.
 *
 * Field mapping is a faithful port of admin-shim `agentToLettaState` /
 * `readBlocksForAgent` — including the locked wire invariants (e.g. `metadata`
 * is `null`, not `{}`; block ids are sha256(`agentId:label`)[..24]).
 *
 * This class is a thin facade: it wires the shared [LocalBackendStoreSupport]
 * into the three per-concern readers and delegates each public query. The reader
 * split (agent / conversation / message) is pure code motion — no behavior change.
 */
class LocalBackendAdminStore(
    private val baseDir: File,
    /** Mirrors admin-shim's `process.env.LMSTUDIO_BASE_URL || "https://api.openai.com/v1"`. */
    private val lmstudioBaseUrl: String =
        System.getenv("LMSTUDIO_BASE_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_ENDPOINT,
) {
    private val support = LocalBackendStoreSupport(baseDir, lmstudioBaseUrl)
    private val agentReader = LocalBackendAgentReader(support)
    private val conversationReader = LocalBackendConversationReader(support)
    private val messageReader = LocalBackendMessageReader(support)

    /** See [LocalBackendAgentReader.listAgentsProjected]. */
    fun listAgentsProjected(limit: Int?, offset: Int?): JsonArray? =
        agentReader.listAgentsProjected(limit, offset)

    /** See [LocalBackendConversationReader.listConversationsProjected]. */
    fun listConversationsProjected(
        agentId: String?,
        archiveStatus: String?,
        limit: Int?,
        offset: Int?,
    ): JsonArray? = conversationReader.listConversationsProjected(agentId, archiveStatus, limit, offset)

    /** See [LocalBackendMessageReader.listMessagesProjected]. */
    fun listMessagesProjected(
        conversationId: String,
        agentId: String?,
        page: MessagePage,
    ): JsonArray? = messageReader.listMessagesProjected(conversationId, agentId, page)

    companion object {
        const val DEFAULT_MODEL_ENDPOINT = "https://api.openai.com/v1"
    }
}
