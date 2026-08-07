package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * lgns8.9: native admin-query tier that reads the letta-code on-disk backend
 * store DIRECTLY, replacing the lettashim HTTP proxy for reads. lettashim is not
 * a proxy — its admin queries are `admin-shim/lib/store.ts` functions reading
 * `<baseDir>/agents/{id}.json` + `<baseDir>/memfs/<agentId>/memory/system/{label}.md`. This
 * ports the `agent.list` read + the `translate.ts:agentToLettaState` projection so
 * the wrapper serves it without the shim.
 *
 * SAFE BY CONSTRUCTION, two ways:
 *  1. constructed only when [baseDir] is explicitly configured (the wrapper
 *     leaves it null unless `LETTA_LOCAL_BACKEND_DIR` is set), and every public
 *     reader returns null on ANY failure so the caller FAILS CLOSED with a typed
 *     capability error — never a fall-back dial to lettashim;
 *  2. **read-only**. Not one reader here opens a file for writing. The epic
 *     constraint is explicit — "do not run multiple writers against one
 *     local-backend root" — so admin WRITES never touch this store: they route
 *     to a native App Server command (which is the store's writer) or fail
 *     closed. See `ToolAdminHandlers` / `ScheduleAdminHandlers`.
 *
 * Field mapping is a faithful port of admin-shim `agentToLettaState` /
 * `readBlocksForAgent` — including the locked wire invariants (e.g. `metadata`
 * is `null`, not `{}`; block ids are sha256(`agentId:label`)[..24]).
 *
 * This class is a thin facade: it wires the shared [LocalBackendStoreSupport]
 * into the per-concern readers (agent / conversation / message / block / run /
 * context) and delegates each public query.
 */
class LocalBackendAdminStore(
    private val baseDir: File,
    /** Mirrors admin-shim's `process.env.LMSTUDIO_BASE_URL || "https://api.openai.com/v1"`. */
    private val lmstudioBaseUrl: String =
        System.getenv("LMSTUDIO_BASE_URL")?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL_ENDPOINT,
) {
    private val support = LocalBackendStoreSupport(baseDir, lmstudioBaseUrl)
    internal val blockReader = LocalBackendBlockReader(support)
    private val agentReader = LocalBackendAgentReader(support, blockReader)
    private val conversationReader = LocalBackendConversationReader(support)
    private val messageReader = LocalBackendMessageReader(support)
    private val runReader = LocalBackendRunReader(support)
    private val contextReader = LocalBackendContextReader(support, messageReader)

    /** See [LocalBackendAgentReader.listAgentsProjected]. */
    fun listAgentsProjected(limit: Int?, offset: Int?): JsonArray? =
        agentReader.listAgentsProjected(limit, offset)

    /** See [LocalBackendAgentReader.countAgents]. */
    fun countAgents(): Int? = agentReader.countAgents()

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

    /** See [LocalBackendBlockReader.listAllBlocks]. */
    fun listBlocksProjected(limit: Int?, offset: Int?): JsonArray? = blockReader.listAllBlocks(limit, offset)

    /** See [LocalBackendBlockReader.blockCount]. */
    fun blockCountProjected(): Int = blockReader.blockCount()

    /** See [LocalBackendBlockReader.getBlock]. */
    fun getBlockProjected(blockId: String): JsonObject? = blockReader.getBlock(blockId)

    /** See [LocalBackendBlockReader.blocksForAgent]. */
    fun blocksForAgentProjected(agentId: String): JsonArray = blockReader.blocksForAgent(agentId)

    /** See [LocalBackendRunReader.listRuns]. */
    internal fun listRunsProjected(query: RunQuery): JsonArray? = runReader.listRuns(query)

    /**
     * bn008.6: set of conversationIds with at least one `active=true` run for
     * [agentId]. Used by the a2a receiver's `conversationsFor` closure to mark
     * a conversation `busy` so the router queues instead of re-triggering.
     *
     * Empty on any read failure — the router will treat every candidate as
     * idle, which is the safe default for a freshness-critical routing decision.
     *
     * **Truncation caveat (N1):** the underlying `limit = 200` is a SAFETY
     * CAP, not a page size. If an agent has more than 200 active runs we
     * return only the first 200's conversation_ids — busy conversations on
     * longer-tailed agents can be reported as idle, causing the a2a router
     * to return [Deliver][IrohAgentMessageRouter.RoutingDecision.Deliver]
     * instead of [Queue][IrohAgentMessageRouter.RoutingDecision.Queue].
     * Layer-2 fix: bump the limit (it caps the JSON page, not the disk walk)
     * or replace the boolean with a separate authoritative count store.
     * Filed as a follow-up bead; not blocking layer-1 because a `busy=false`
     * miss at most costs a same-runtime second turn, not a deliver/queue
     * correctness violation.
     */
    fun activeConversationIds(agentId: String): Set<String> = runCatching {
        val runs = runReader.listRuns(
            RunQuery(agentId = agentId, active = true, limit = 200),
        ) ?: return@runCatching emptySet()
        runs.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val prim = obj["conversation_id"] as? JsonPrimitive
            if (prim != null && prim.isString) prim.content else null
        }.toSet()
    }.getOrDefault(emptySet())

    /** See [LocalBackendRunReader.getRun]. */
    fun getRunProjected(runId: String): JsonObject? = runReader.getRun(runId)

    /** See [LocalBackendRunReader.listSteps]. */
    internal fun listStepsProjected(runId: String, query: StepQuery): JsonArray? =
        runReader.listSteps(runId, query)

    /** See [LocalBackendRunReader.runExists]. */
    fun runExists(runId: String): Boolean = runReader.runExists(runId)

    /** See [LocalBackendContextReader.agentContextProjected]. */
    fun agentContextProjected(agentId: String, conversationId: String?): JsonObject? =
        contextReader.agentContextProjected(agentId, conversationId)

    companion object {
        const val DEFAULT_MODEL_ENDPOINT = "https://api.openai.com/v1"
    }
}
