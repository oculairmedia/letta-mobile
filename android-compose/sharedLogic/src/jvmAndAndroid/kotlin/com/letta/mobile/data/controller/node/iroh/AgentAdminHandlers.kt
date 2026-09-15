package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Agent CRUD handlers for the Iroh admin RPC router.
 *
 * Phase 2: app_server_v2-owned operations are fail-closed on the native client.
 * There is no LettaShim or direct-disk fallback for these methods.
 */
object AgentAdminHandlers {
    fun register(
        router: AdminRpcRouter,
        controller: AppServerController? = null,
        tiers: NativeReadTiers = NativeReadTiers(),
    ) {
        registerAgentList(router, tiers)
        registerAgentGet(router, tiers)
        registerAgentCreate(router, tiers)
        registerAgentUpdate(router, controller, tiers)
        registerAgentDelete(router, tiers)
        // letta-mobile-ulz2b.1: authoritative scalar roster size from the on-disk
        // agent catalog. Completeness is the directory listing itself — never a
        // client agent.list .size. Absent store fails closed (same contract as
        // agent.context / block.list); no admin-HTTP / silent-0 fallback.
        registerAgentCount(router, tiers.localBackendStore)
        registerAgentContext(router, tiers.localBackendStore)
    }

    // The App Server drops agent `metadata`; Meridian keeps it (see AgentMetadataSidecar), so every
    // agent returned below goes through [overlaid] and every write through the sidecar.

    private fun registerAgentList(router: AdminRpcRouter, tiers: NativeReadTiers) {
        router.register("agent.list") { params ->
            val limit = param(params, AdminParamKey("limit"))?.toLongOrNull()?.coerceAtLeast(1L)
            val offset = param(params, AdminParamKey("offset"))?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            NativeAdmin.require(tiers.nativeClient, NativeAdminOp.AgentList) { c ->
                // letta-mobile-pu7j7: the lc-local-backend pages by `after`
                // cursor and has NO offset concept — a forwarded offset was
                // silently dropped, so every page of a paged roster sweep
                // returned the same first ~20 agents (the store's default
                // limit) and agents beyond page 1 never resolved. Emulate
                // offset here: fetch offset+limit rows in one read (the store
                // is in-memory) and slice locally, preserving the admin RPC's
                // limit/offset contract for both clients.
                val pageSize = limit ?: DEFAULT_AGENT_LIST_LIMIT
                val fetch = (offset + pageSize).coerceAtMost(MAX_AGENT_LIST_FETCH)
                val response = c.agentList(
                    AppServerCommand.AgentList(
                        requestId = NativeAdmin.requestId(),
                        query = NativeAdmin.queryOf("limit" to fetch.toString()),
                    ),
                )
                if (!response.success) return@require null
                val page = pageOf(response.agents ?: JsonArray(emptyList()), offset, pageSize)
                tiers.agentMetadata?.overlayAll(page) ?: page
            }
        }
    }

    private fun pageOf(all: JsonArray, offset: Long, pageSize: Long): JsonArray =
        if (offset == 0L && all.size <= pageSize) all else JsonArray(all.drop(offset.toInt()).take(pageSize.toInt()))

    /** An admin RPC method addressed by `agent_id`, served by one native [op]. */
    private class AgentByIdMethod(val name: String, val op: NativeAdminOp)

    /** The native call behind an [AgentByIdMethod]; null means it failed. */
    private fun interface AgentByIdCall {
        suspend fun invoke(client: AppServerClient, agentId: String): JsonElement?
    }

    private fun AdminRpcRouter.registerById(method: AgentByIdMethod, tiers: NativeReadTiers, call: AgentByIdCall) {
        register(method.name) { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            NativeAdmin.require(tiers.nativeClient, method.op) { c -> call.invoke(c, id) }
        }
    }

    private fun registerAgentGet(router: AdminRpcRouter, tiers: NativeReadTiers) =
        router.registerById(AgentByIdMethod("agent.get", NativeAdminOp.AgentGet), tiers) { c, id ->
            val response = c.agentRetrieve(AppServerCommand.AgentRetrieve(requestId = NativeAdmin.requestId(), agentId = id))
            if (response.success) tiers.agentMetadata.overlaid(response.agent) else null
        }

    private fun registerAgentCreate(router: AdminRpcRouter, tiers: NativeReadTiers) {
        router.register("agent.create") { params ->
            val body = params.withDefaultContextWindow()
            NativeAdmin.require(tiers.nativeClient, NativeAdminOp.AgentCreate) { c ->
                val response = c.agentCreate(
                    AppServerCommand.AgentCreate(
                        requestId = NativeAdmin.requestId(),
                        body = body,
                    ),
                )
                if (!response.success) return@require null
                storeMetadata(tiers.agentMetadata, agentIdOf(response.agent), metadataOf(body))
                tiers.agentMetadata.overlaid(response.agent)
            }
        }
    }

    private fun registerAgentUpdate(router: AdminRpcRouter, controller: AppServerController?, tiers: NativeReadTiers) {
        router.register("agent.update") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            // Never inject a default context_window_limit on update — model-only
            // patches (Desktop / AdminChatModelCoordinator) must keep the agent's
            // existing limit. Defaults apply on create only.
            val body = params ?: buildJsonObject { }
            val sidecar = tiers.agentMetadata
            val result = NativeAdmin.require(tiers.nativeClient, NativeAdminOp.AgentUpdate) { c ->
                val metadataOnly = metadataOnlyPatch(body)
                if (sidecar != null && metadataOnly != null) {
                    updateMetadataOnly(c, sidecar, id, metadataOnly)
                } else {
                    updateThroughAppServer(c, sidecar, id, body)
                }
            }
            if (RuntimeInvalidationPolicy.agentUpdateRequiresRestart(params)) {
                controller?.stopRuntime(AgentId(id))
            }
            result
        }
    }

    /**
     * A metadata-only patch never reaches the App Server: it would drop the metadata and still
     * rewrite the agent's stored record. The retrieve proves the agent exists.
     */
    private suspend fun updateMetadataOnly(
        client: AppServerClient,
        sidecar: AgentMetadataSidecar,
        id: String,
        metadata: JsonObject,
    ): JsonElement? {
        val existing = client.agentRetrieve(AppServerCommand.AgentRetrieve(requestId = NativeAdmin.requestId(), agentId = id))
        if (!existing.success || existing.agent == null) return null
        sidecar.write(id, metadata)
        return sidecar.overlay(existing.agent)
    }

    private suspend fun updateThroughAppServer(
        client: AppServerClient,
        sidecar: AgentMetadataSidecar?,
        id: String,
        body: JsonObject,
    ): JsonElement? {
        val response = client.agentUpdate(AppServerCommand.AgentUpdate(requestId = NativeAdmin.requestId(), agentId = id, body = body))
        if (!response.success) return null
        storeMetadata(sidecar, id, metadataOf(body))
        return sidecar.overlaid(response.agent)
    }

    private fun registerAgentDelete(router: AdminRpcRouter, tiers: NativeReadTiers) =
        router.registerById(AgentByIdMethod("agent.delete", NativeAdminOp.AgentDelete), tiers) { c, id ->
            val response = c.agentDelete(AppServerCommand.AgentDelete(requestId = NativeAdmin.requestId(), agentId = id))
            if (response.success) {
                tiers.agentMetadata?.delete(id)
                buildJsonObject { put("deleted", true) }
            } else {
                null
            }
        }

    private fun metadataOf(body: JsonObject): JsonObject? = body[AgentMetadataSidecar.METADATA_KEY] as? JsonObject

    /** The patch's metadata when `metadata` is all it changes (besides naming the agent), else null. */
    private fun metadataOnlyPatch(body: JsonObject): JsonObject? {
        val metadata = metadataOf(body) ?: return null
        return metadata.takeIf { body.keys.all { it == "agent_id" || it == AgentMetadataSidecar.METADATA_KEY } }
    }

    private fun agentIdOf(agent: JsonElement?): String? = ((agent as? JsonObject)?.get("id") as? JsonPrimitive)?.content

    /** Keeps [metadata] for [agentId] when there is a sidecar, an id and metadata to keep. */
    private fun storeMetadata(sidecar: AgentMetadataSidecar?, agentId: String?, metadata: JsonObject?) {
        val target = sidecar ?: return
        val id = agentId ?: return
        target.write(id, metadata ?: return)
    }

    private fun registerAgentCount(router: AdminRpcRouter, store: LocalBackendAdminStore?) {
        if (store == null) {
            CapabilityUnavailable.register(router, setOf("agent.count"), service = "local_backend_store")
            return
        }
        router.register("agent.count") { _ ->
            val count = store.countAgents()
                ?: adminError("agent.count could not read the local backend agent store")
            JsonPrimitive(count)
        }
    }

    /**
     * lgns8.9: `agent.context` is served from the on-disk local backend store.
     *
     * admin-shim's `GET /v1/agents/{id}/context` was itself a store read
     * (`handleAgentContext`: agent record + `system-prompt.json` + the transcript
     * fan-out), so [LocalBackendContextReader] is the same computation without
     * the HTTP hop. The pinned App Server v2 inventory has no context command, so
     * with no store configured the method fails closed — never a shim dial.
     *
     * The page guard still runs on the result: context carries a full transcript,
     * and bounding it is controller-owned regardless of source.
     */
    private fun registerAgentContext(router: AdminRpcRouter, store: LocalBackendAdminStore?) {
        if (store == null) {
            CapabilityUnavailable.register(router, setOf("agent.context"), service = "local_backend_store")
            return
        }
        router.register("agent.context") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            val context = store.agentContextProjected(id, param(params, AdminParamKey("conversation_id")))
                ?: adminError("agent $id not found")
            MessageListPageGuard.boundObjectStringFields(
                MessageListPageGuard.dropField(context, "messages"),
            )
        }
    }


    /** Mirrors the lc-local-backend default page size for agent.list. */
    private fun AgentMetadataSidecar?.overlaid(agent: kotlinx.serialization.json.JsonElement?) = this?.overlay(agent) ?: agent

    private const val DEFAULT_AGENT_LIST_LIMIT = 20L

    /**
     * letta-mobile-pu7j7: ceiling for the offset-emulation fetch so a bogus
     * offset cannot request an unbounded read. Far above any real roster
     * (~100 agents in production stores).
     */
    private const val MAX_AGENT_LIST_FETCH = 10_000L

}
