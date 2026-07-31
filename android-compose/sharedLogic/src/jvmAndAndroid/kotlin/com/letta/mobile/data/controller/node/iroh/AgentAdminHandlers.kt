package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.AppServerController
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
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
        val nativeClient = tiers.nativeClient
        router.register("agent.list") { params ->
            val limit = param(params, AdminParamKey("limit"))?.toLongOrNull()?.coerceAtLeast(1L)
            val offset = param(params, AdminParamKey("offset"))?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            NativeAdmin.require(nativeClient, NativeAdminOp.AgentList) { c ->
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
                when {
                    !response.success -> null
                    else -> {
                        val all = response.agents ?: JsonArray(emptyList())
                        if (offset == 0L && all.size <= pageSize) {
                            all
                        } else {
                            JsonArray(all.drop(offset.toInt()).take(pageSize.toInt()))
                        }
                    }
                }
            }
        }
        router.register("agent.get") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            NativeAdmin.require(nativeClient, NativeAdminOp.AgentGet) { c ->
                val response = c.agentRetrieve(
                    AppServerCommand.AgentRetrieve(requestId = NativeAdmin.requestId(), agentId = id),
                )
                if (response.success) response.agent else null
            }
        }
        router.register("agent.create") { params ->
            val body = params.withDefaultContextWindow()
            NativeAdmin.require(nativeClient, NativeAdminOp.AgentCreate) { c ->
                val response = c.agentCreate(
                    AppServerCommand.AgentCreate(
                        requestId = NativeAdmin.requestId(),
                        body = body,
                    ),
                )
                if (response.success) response.agent else null
            }
        }
        router.register("agent.update") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            // Never inject a default context_window_limit on update — model-only
            // patches (Desktop / AdminChatModelCoordinator) must keep the agent's
            // existing limit. Defaults apply on create only.
            val body = params
            val result = NativeAdmin.require(nativeClient, NativeAdminOp.AgentUpdate) { c ->
                val response = c.agentUpdate(
                    AppServerCommand.AgentUpdate(
                        requestId = NativeAdmin.requestId(),
                        agentId = id,
                        body = body ?: buildJsonObject { },
                    ),
                )
                if (response.success) response.agent else null
            }
            if (RuntimeInvalidationPolicy.agentUpdateRequiresRestart(params)) {
                controller?.stopRuntime(AgentId(id))
            }
            result
        }
        router.register("agent.delete") { params ->
            val id = params.requireParam(AdminParamKey("agent_id"))
            NativeAdmin.require(nativeClient, NativeAdminOp.AgentDelete) { c ->
                val response = c.agentDelete(
                    AppServerCommand.AgentDelete(requestId = NativeAdmin.requestId(), agentId = id),
                )
                if (response.success) buildJsonObject { put("deleted", true) } as JsonObject else null
            }
        }
        registerAgentContext(router, tiers.localBackendStore)
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
    private const val DEFAULT_AGENT_LIST_LIMIT = 20L

    /**
     * letta-mobile-pu7j7: ceiling for the offset-emulation fetch so a bogus
     * offset cannot request an unbounded read. Far above any real roster
     * (~100 agents in production stores).
     */
    private const val MAX_AGENT_LIST_FETCH = 10_000L

}
