package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * lgns8.9 disposition for the tool-library and memory-block domains.
 *
 * ## Tools — controller-native constant catalog
 * admin-shim's `GET /v1/tools` and `/v1/tools/{id}` return
 * `BUILTIN_TOOL_DEFINITIONS.map(vanillaTool)`: a hard-coded 14-entry list of
 * letta-code's client-side tools. No datastore is involved, so the catalog moves
 * into the controller verbatim ([NativeAdminCatalogs.toolCatalog]).
 *
 * Tool CRUD (`PUT /v1/tools`, `PATCH|DELETE /v1/tools/{id}`) and agent
 * attach/detach do not exist in admin-shim — those paths 404 — because
 * letta-code tools are code-defined, not user-authored records. The pinned v2
 * inventory's `update_toolset` is the RUNTIME toolset for a live session, not an
 * admin mutation of a tool library, and conflating them would silently reshape
 * turn behaviour. All six therefore fail closed.
 *
 * ## Blocks — store reads, native writes, denied where neither exists
 * Blocks are `memfs/<agentId>/memory/system/<label>.md` files:
 *  - `block.list` / `block.get` read them via [LocalBackendBlockReader], exactly
 *    as admin-shim did (`handleBlocksList` / `handleBlockDetail`);
 *  - `block.update_agent` (agent + label, the memory editor's write) maps 1:1
 *    onto the native `write_memory_file` command, so the App Server — the
 *    store's single writer — performs the write. The controller never writes the
 *    local-backend root itself (epic constraint: one writer per root);
 *  - `block.create`, `block.update`, `block.delete`, `block.attach` and
 *    `block.detach` address a block by a GLOBAL id that admin-shim synthesises
 *    (`sha256(agentId:label)`) and no native command accepts. Global creation and
 *    attach/detach have no meaning at all in a per-agent memory filesystem, and
 *    admin-shim 404s every one of these routes. They fail closed.
 */
object ToolAdminHandlers {
    internal fun register(
        router: AdminRpcRouter,
        store: LocalBackendAdminStore?,
        nativeClient: AppServerClient?,
    ) {
        registerToolMethods(router)
        registerBlockReads(router, store)
        registerBlockWrites(router, nativeClient)
    }

    private fun registerToolMethods(router: AdminRpcRouter) {
        router.register("tool.list") { NativeAdminCatalogs.toolCatalog() }
        router.register("tool.get") { params ->
            val toolId = params.requireParam(AdminParamKey("tool_id"))
            NativeAdminCatalogs.tool(toolId) ?: adminError("tool $toolId not found")
        }
        CapabilityUnavailable.denyFailClosed(
            router,
            TOOL_WRITE_METHODS,
            reason = "letta-code tools are code-defined, not user-authored records: admin-shim 404s " +
                "every tool CRUD/attach route, and the pinned App Server v2 inventory has no tool-library " +
                "command (update_toolset is the live-session toolset, not admin CRUD); upstream must expose one",
        )
    }

    private fun registerBlockReads(router: AdminRpcRouter, store: LocalBackendAdminStore?) {
        if (store == null) {
            CapabilityUnavailable.register(router, BLOCK_READ_METHODS, service = "local_backend_store")
            return
        }
        router.register("block.list") { params ->
            // letta-mobile post-cutover regression (2026-08-01): the union of every
            // agent's memory files is ~1.83 MB on the live host, over the 1 MiB
            // admin_rpc frame cap, so an unwindowed block.list could not be
            // delivered at all. limit/offset mirror agent.list exactly: an absent
            // limit means DEFAULT_BLOCK_LIST_LIMIT (not "everything"), because a
            // default of "everything" is what broke.
            val limit = param(params, AdminParamKey("limit"))?.toIntOrNull()
                ?.coerceIn(1, MAX_BLOCK_LIST_LIMIT)
                ?: DEFAULT_BLOCK_LIST_LIMIT
            val offset = param(params, AdminParamKey("offset"))?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val page = store.listBlocksProjected(limit, offset)
                ?: adminError("block.list could not read the local backend memory store")
            val total = store.blockCountProjected()
            // Dual shape, mirroring MessageListPageGuard: a bare array when the
            // FULL set was delivered (byte-identical to the pre-paging contract,
            // so an older client is unaffected), an envelope carrying an
            // authoritative `total`/`has_more` whenever it was not. The client
            // must never have to infer "more exists" from page size — inferring it
            // is what produced a wrong exact count against a backend that ignored
            // limit/offset entirely.
            if (offset == 0 && page.size >= total) {
                page
            } else {
                buildJsonObject {
                    put("blocks", page)
                    put("total", total)
                    put("offset", offset)
                    put("limit", limit)
                    put("has_more", offset + page.size < total)
                }
            }
        }
        router.register("block.get") { params ->
            val blockId = params.requireParam(AdminParamKey("block_id"))
            store.getBlockProjected(blockId) ?: adminError("block $blockId not found")
        }
    }

    private fun registerBlockWrites(router: AdminRpcRouter, nativeClient: AppServerClient?) {
        CapabilityUnavailable.denyFailClosed(
            router,
            BLOCK_DENIED_METHODS,
            reason = "the letta-code local backend has no globally addressable block entity — blocks are " +
                "per-agent memfs files, so global create and agent attach/detach have no meaning and " +
                "admin-shim 404s those routes; use block.update_agent (agent_id + label), which the App " +
                "Server owns natively",
        )
        router.register("block.update_agent") { params ->
            val agentId = params.requireParam(AdminParamKey("agent_id"))
            val label = params.requireParam(AdminParamKey("label"))
            val value = param(params, AdminParamKey("value"))
                ?: adminError("value required: block.update_agent writes the memory file contents")
            val client = nativeClient
                ?: adminError("capability_unavailable: block.update_agent requires the native App Server client")
            val response = client.writeMemoryFile(
                AppServerCommand.WriteMemoryFile(
                    requestId = NativeAdmin.requestId(),
                    agentId = agentId,
                    path = memoryPathFor(label),
                    content = value,
                    commitMessage = "block.update_agent: $label",
                ),
            )
            if (!response.success) adminError(response.error ?: "write_memory_file failed")
            // Echo the post-write block using the SAME projection block.get serves,
            // so the client decodes one shape regardless of which route it used.
            LocalBackendBlockReader.projectBlock(agentId, label, value)
        }
    }

    /** MemFS path for a core-memory block label, mirroring `memory/system/<label>.md`. */
    private fun memoryPathFor(label: String): String = "system/$label.md"

    /** Constant catalog reads (no datastore). */
    val TOOL_CATALOG_METHODS: Set<String> = setOf("tool.list", "tool.get")

    /** Permanently denied: no admin tool-library surface exists anywhere. */
    val TOOL_WRITE_METHODS: Set<String> = setOf(
        "tool.create",
        "tool.update",
        "tool.delete",
        "tool.attach",
        "tool.detach",
    )

    /** Served from the on-disk memfs memory files. */
    val BLOCK_READ_METHODS: Set<String> = setOf("block.list", "block.get")

    /**
     * Page size when the caller sends no `limit`. Blocks are capped at
     * [LocalBackendBlockReader.BLOCK_VALUE_LIMIT] chars, so 50 x ~5 KB worst case
     * stays an order of magnitude under the 1 MiB admin_rpc frame cap.
     */
    const val DEFAULT_BLOCK_LIST_LIMIT: Int = 50

    /** Hard ceiling so a client cannot request its way back over the frame cap. */
    const val MAX_BLOCK_LIST_LIMIT: Int = 200

    /** Served by the native `write_memory_file` command. */
    val BLOCK_NATIVE_WRITE_METHODS: Set<String> = setOf("block.update_agent")

    /** Permanently denied: no global block entity, no native command. */
    val BLOCK_DENIED_METHODS: Set<String> = setOf(
        "block.create",
        "block.update",
        "block.delete",
        "block.attach",
        "block.detach",
    )

    val METHODS: Set<String> = TOOL_CATALOG_METHODS + TOOL_WRITE_METHODS +
        BLOCK_READ_METHODS + BLOCK_NATIVE_WRITE_METHODS + BLOCK_DENIED_METHODS
}
